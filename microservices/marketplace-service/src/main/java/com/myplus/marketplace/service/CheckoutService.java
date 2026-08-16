package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.TaxPolicyView;
import com.myplus.commerce.domain.TaxMath;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.dto.CheckoutDTO;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.Cart;
import com.myplus.marketplace.entity.CartItem;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checkout (slice 69 E5 + slice 72 E13 coupons). Totals are computed server-side from the persistent cart (slice 68):
 * subtotal from the cart's snapshotted prices, EXCLUSIVE tax from each line's snapshotted rate, a server-priced
 * shipping fee, and an optional coupon discount off the subtotal. {@link #place} builds a trustworthy OrderDTO and
 * delegates to the existing {@link OrderService#placePublic} reserve→charge→confirm saga. Client money is ignored.
 */
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

    private static final int SCALE = 2;

    /** What a tenant looks like when tax is off — also the fail-closed answer when the books are unreachable. */
    private static final TaxPolicyView TAX_OFF =
            TaxPolicyView.builder().enabled(false).mode("EXCLUSIVE").defaultRate(BigDecimal.ZERO).build();
    /**
     * Per-tenant policy cache. An INSTANCE field, not static: the bean is a singleton so it lives just as long,
     * and static mutable state shared between unit tests is how one test's tenant silently answers another's.
     */
    private final Map<Long, CachedTaxPolicy> taxPolicyCache = new ConcurrentHashMap<>();

    /**
     * How long a tenant's tax policy is reused before re-reading it. Same shape and default as
     * business-service's {@code app.period-lock.cache-ttl-ms}, and for the same reason: this is configuration
     * that changes at month-end sitting on a hot path. An owner who flips the switch waits at most this long
     * for the storefront to follow — a bounded lag, not the permanent disagreement this change removes.
     */
    @org.springframework.beans.factory.annotation.Value("${app.tax-policy.cache-ttl-ms:15000}")
    private long taxPolicyTtlMs = 15_000L;

    private final CartService cartService;
    private final OrderService orderService;
    private final CouponService couponService;
    private final TradeClient tradeClient;         // the books own the tax policy; this service asks for it
    private final ShippingPolicy shippingPolicy;   // O3: per-org delivery fees + the COD policy
    private final BackorderPolicy backorderPolicy; // O5c: what will have to wait, shown before the shopper commits

    /** Live totals for the current cart + chosen shipping method + optional coupon — no order is placed. */
    @Transactional(readOnly = true)
    public CheckoutDTO.Quote quote(Long org, String cartToken, String shippingMethod, String couponCode) {
        if (org == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(shippingMethod);
        Cart cart = cartService.activeCart(org, cartToken).orElse(null);
        Totals t = totals(cart, option, org);
        CouponService.CouponResult cr = couponService.validateAndCompute(org, couponCode, t.subtotal);
        CheckoutDTO.Quote q = assemble(t, option, cr, org);
        applyBackorderWarning(q, cart, org);   // O5c: tell the shopper BEFORE they commit
        return q;
    }

    /** Place the order from the server cart. Validates cart + address, applies a coupon, then delegates to the saga. */
    @Transactional
    public OrderDTO place(CheckoutDTO.Request req) {
        if (req.getOrganizationId() == null) throw new ValidationException("Store (organizationId) is required");
        ShippingOption option = ShippingOption.from(req.getShippingMethod());

        Cart cart = cartService.activeCart(req.getOrganizationId(), req.getCartToken()).orElse(null);
        if (cart == null || cart.getItems().isEmpty()) throw new ValidationException("Your cart is empty");
        if (option.requiresAddress() && isBlank(req.getShippingAddress()))
            throw new ValidationException("A delivery address is required for " + option.name() + " shipping");

        // OMS O3: the SERVER enforces the COD policy. The storefront also hides the option, but a hidden radio
        // button is not a control — anything posting to this endpoint would otherwise place a COD order at a
        // store that does not accept cash.
        if ("COD".equalsIgnoreCase(req.getPaymentMode()) && !shippingPolicy.codEnabled(req.getOrganizationId()))
            throw new ValidationException("This store does not accept cash on delivery. Please pay by card.");

        Totals t = totals(cart, option, req.getOrganizationId());
        CouponService.CouponResult cr = couponService.validateAndCompute(req.getOrganizationId(), req.getCouponCode(), t.subtotal);
        BigDecimal discount = cr.discount();
        BigDecimal total = t.subtotal.subtract(discount).add(t.taxTotal).add(t.shippingFee);
        boolean applied = discount.signum() > 0;

        OrderDTO dto = new OrderDTO();
        dto.setOrganizationId(req.getOrganizationId());
        dto.setCustomerName(req.getCustomerName());
        dto.setCustomerContact(req.getCustomerContact());
        dto.setShippingAddress(req.getShippingAddress());
        dto.setPaymentMode(req.getPaymentMode());
        dto.setCardToken(req.getCardToken());
        dto.setCustomerToken(req.getCustomerToken());
        dto.setCartToken(req.getCartToken());          // placePublic closes this cart on success
        dto.setShippingMethod(option.name());
        dto.setSubTotal(t.subtotal);
        dto.setTaxTotal(t.taxTotal);
        dto.setShippingFee(t.shippingFee);
        dto.setDiscountAmount(discount);
        dto.setCouponCode(applied ? cr.code() : null);
        dto.setTotal(total);                           // authoritative grand total — the charge uses this
        dto.setItems(toOrderLines(cart));              // server-sourced items + prices

        OrderDTO placed = orderService.placePublic(dto);
        if (applied) couponService.recordUse(cr.couponId());   // count the redemption only when it actually applied
        return placed;
    }

    /**
     * OMS O5c — mark on the quote what will have to wait.
     *
     * <p>Uses the SAME {@link BackorderPolicy} the checkout does, so the shopper cannot be shown one thing and
     * charged another. Silent on failure: a warning that cannot be computed must not block a quote, and the
     * checkout re-runs the split authoritatively anyway.
     */
    private void applyBackorderWarning(CheckoutDTO.Quote q, Cart cart, Long org) {
        if (q == null || cart == null || cart.getItems().isEmpty()) return;

        java.util.Map<Long, Integer> requested = new java.util.LinkedHashMap<>();
        for (CartItem it : cart.getItems()) {
            if (it.getProductId() == null || it.getQuantity() == null || it.getQuantity() <= 0) continue;
            requested.merge(it.getProductId(), it.getQuantity(), Integer::sum);
        }
        BackorderSplit.Result split = backorderPolicy.splitFor(org, requested);
        if (split == null) return;                     // off, unreadable, or nothing short — say nothing

        java.util.Map<Long, Integer> owed = new java.util.HashMap<>();
        for (BackorderSplit.LineSplit l : split.lines()) owed.merge(l.productId(), l.backordered(), Integer::sum);
        if (q.getItems() != null)
            for (CheckoutDTO.Line l : q.getItems())
                l.setBackorderedQuantity(owed.getOrDefault(l.getProductId(), 0));

        q.setHasBackorder(true);
        q.setPromisedDate(backorderPolicy.promisedDate(org));
    }

    // --- internals ---------------------------------------------------------------------------------------------

    private record Totals(List<CheckoutDTO.Line> lines, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal shippingFee) {}

    private Totals totals(Cart cart, ShippingOption option, Long org) {
        List<CheckoutDTO.Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        // Tax the way the BOOKS will, not the way this service used to guess.
        //
        // This loop previously did `net × item.taxRate / 100` — its own tax engine, with no tenant switch,
        // no org default rate and no INCLUSIVE handling. business-service gates every line on
        // `tax_setting.enabled`, which DEFAULTS TO FALSE for a tenant that has never configured tax, so a
        // shop with tax off was shown a tax line and quoted a total its own invoice then contradicted:
        // quoted 22, invoiced 20. Each side was locally right; only the disagreement was wrong.
        //
        // The policy now comes from the owner of the books over the trade contract, and the arithmetic is
        // the shared `TaxMath` both sides call — one rule, one implementation.
        TaxPolicyView taxPolicy = taxPolicyFor(org);
        if (cart != null) {
            for (CartItem it : cart.getItems()) {
                BigDecimal unit = nz(it.getUnitPrice());
                int qty = it.getQuantity() != null ? it.getQuantity() : 0;
                BigDecimal lineAmount = unit.multiply(BigDecimal.valueOf(qty)).setScale(SCALE, RoundingMode.HALF_UP);
                TaxMath.TaxAmounts t = TaxMath.forLine(lineAmount, nz(it.getTaxRate()),
                        taxPolicy.isEnabled(), taxPolicy.getDefaultRate(), taxPolicy.isInclusive());
                // INCLUSIVE prices already contain the tax, so the goods subtotal is the amount with the tax
                // BACKED OUT — taking `lineAmount` here would count it twice. EXCLUSIVE leaves net unchanged.
                BigDecimal net = t.net();
                BigDecimal lineTax = t.tax();
                subtotal = subtotal.add(net);
                taxTotal = taxTotal.add(lineTax);
                lines.add(CheckoutDTO.Line.builder()
                        .productId(it.getProductId()).name(it.getProductName())
                        .unitPrice(unit).quantity(qty).lineTotal(net).lineTax(lineTax).build());
            }
        }
        // OMS O3: the fee is resolved PER ORG, and the free-delivery threshold is measured against the goods
        // subtotal — so it must be computed here, after the lines are summed, not read off the enum. Both
        // quote() and place() reach this one method, which is what keeps a shown price and a charged price
        // identical.
        BigDecimal fee = shippingPolicy.feeFor(option, subtotal, org).setScale(SCALE, RoundingMode.HALF_UP);
        return new Totals(lines, subtotal, taxTotal, fee);
    }

    /**
     * The tenant's tax policy, cached briefly — the storefront quote is a hot path.
     *
     * <p>Tax configuration changes at month-end, not per request, so a short TTL keeps a remote call off
     * every keystroke-driven re-quote while still letting an owner's change take effect without a restart.
     * Same reasoning (and roughly the same window) as business-service's period-lock cache.
     *
     * <p><b>Fails CLOSED, deliberately.</b> If business-service cannot be reached we treat tax as OFF rather
     * than guessing a rate. Charging a shopper tax this service invented — which the invoice would then not
     * record — is the exact defect this change exists to remove; showing no tax during an outage is visibly
     * wrong to the shopkeeper and recoverable, while an invented tax line is neither. The alternative,
     * failing the quote outright, would take the storefront down for a configuration read.
     *
     * <p><b>On the remote call sitting inside a transaction.</b> Both callers are {@code @Transactional}, so a
     * MISS holds a pooled connection for the round trip. That is why the miss is rare by construction (one per
     * tenant per TTL, not one per quote) and why the catch is here rather than at the caller: an exception
     * escaping into the caller's transaction would mark it rollback-only and turn a configuration read into a
     * failed checkout.
     */
    private TaxPolicyView taxPolicyFor(Long org) {
        long now = System.currentTimeMillis();
        CachedTaxPolicy hit = taxPolicyCache.get(org);
        if (hit != null && hit.expiresAt() > now) return hit.policy();
        TaxPolicyView fetched;
        try {
            fetched = AsOrg.call(org, tradeClient::taxPolicy);
            if (fetched == null) fetched = TAX_OFF;
        } catch (RuntimeException ex) {
            LOG.warn("Tax policy unavailable for org {} ({}: {}) — quoting with tax OFF for {}ms",
                    org, ex.getClass().getSimpleName(), ex.getMessage(), taxPolicyTtlMs);
            fetched = TAX_OFF;
        }
        taxPolicyCache.put(org, new CachedTaxPolicy(fetched, now + taxPolicyTtlMs));
        return fetched;
    }

    private record CachedTaxPolicy(TaxPolicyView policy, long expiresAt) {}

    private CheckoutDTO.Quote assemble(Totals t, ShippingOption option, CouponService.CouponResult cr, Long org) {
        BigDecimal discount = cr.discount();
        BigDecimal total = t.subtotal.subtract(discount).add(t.taxTotal).add(t.shippingFee);
        return CheckoutDTO.Quote.builder()
                .items(t.lines).subtotal(t.subtotal).discount(discount)
                .taxTotal(t.taxTotal).shippingFee(t.shippingFee).total(total)
                .shippingMethod(option.name()).couponCode(cr.code()).couponMessage(cr.message())
                .addressRequired(option.requiresAddress())
                .codEnabled(shippingPolicy.codEnabled(org))   // O3: so the storefront offers only what is accepted
                .build();
    }

    private List<OrderDTO.Line> toOrderLines(Cart cart) {
        List<OrderDTO.Line> lines = new ArrayList<>();
        for (CartItem it : cart.getItems()) {
            OrderDTO.Line l = new OrderDTO.Line();
            l.setProductId(it.getProductId());
            // OMS O4: the cart already knows what it is selling. Dropping the name here is why order detail
            // could only ever say "Product 42" — and why it could not be recovered later without asking the
            // catalog what the product is called TODAY, which is not what the order sold.
            l.setProductName(it.getProductName());
            l.setQuantity(it.getQuantity());
            l.setPrice(nz(it.getUnitPrice()));
            lines.add(l);
        }
        return lines;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
