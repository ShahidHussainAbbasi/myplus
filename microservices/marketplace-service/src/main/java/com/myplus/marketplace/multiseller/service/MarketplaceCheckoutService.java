package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.marketplace.multiseller.domain.MarketplaceRuleException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Payment;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Settlement;
import com.myplus.marketplace.multiseller.domain.PhaseGuard;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.service.ShippingPolicy;
import com.myplus.marketplace.support.AsOrg;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e — the shopper's side: one-seller cash-on-delivery checkout, and tracking.
 * Contract: docs/slices/mkt-1e-checkout-acceptance.md
 *
 * <h3>Never optimistic (source §18.5, standard §0b)</h3>
 * A successful checkout means "the stock is held and the seller has been asked", never "confirmed". The order is
 * SUBMITTED and its seller order OFFERED until the seller accepts ({@link SellerOrderService}).
 *
 * <h3>Two short transactions, the remote hold between them</h3>
 * tx 1 writes the order, its seller order and the snapshot lines and commits; the hold is then asked for outside
 * any transaction (a slow inventory never holds a database connection or the order-number lock); tx 2 records the
 * answer. A crash between them leaves an UNASSIGNED seller order that the sweeper cancels and releases by its key.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceCheckoutService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceCheckoutService.class);

    /** MKT- numbers are one PLATFORM series: org 0 is the platform, never a tenant id. */
    static final long PLATFORM_ORG = 0L;
    static final String DOC_TYPE = "MKT";
    static final int MAX_QUANTITY = 10;
    static final String COD = "COD";
    /** MKT-1e2: paid online by a signed-in customer, collected by MaxTheService. */
    static final String CARD = "CARD";
    static final String DECLINED = "Your card was declined. Please use another card or choose cash on delivery.";
    /**
     * At most this many orders per phone waiting for sellers at once. A checkout HOLDS a seller's stock, and the
     * route is anonymous, so without a bound a script could keep a seller's whole stock held. A real shopper rarely
     * has more than one order waiting; three leaves room for a family. The gateway rate limiter is the other half.
     */
    static final int MAX_OPEN_PER_PHONE = 3;

    private final MarketplaceOfferRepository offers;
    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceProductRepository products;
    private final MarketplacePolicyRepository policies;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final MarketplaceOrderRepository orders;
    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderLineRepository lines;
    private final PublicOfferService publicOffers;
    private final MarketplaceSettingsService settings;
    private final ShippingPolicy shippingPolicy;
    private final TradeClient trade;
    private final DocumentNumberService numbers;
    private final SellerAccess access;
    private final PlatformTransactionManager txManager;
    private final MarketplacePaymentService payments;
    /** The seller side releases holds; it also calls {@link #hold}, so it is resolved on demand (a real cycle). */
    private final org.springframework.beans.factory.ObjectProvider<SellerOrderService> sellerSide;

    // ── checkout ───────────────────────────────────────────────────────────────────────────────────────

    /** Anonymous (MKT-1e). */
    public MarketplaceOrderDTOs.OrderView checkout(MarketplaceOrderDTOs.CheckoutRequest req) {
        return checkout(req, null);
    }

    /**
     * Not {@code @Transactional}: it runs its own two transactions with the remote hold (and, for a card, the charge)
     * between them. {@code customerId}: the signed-in account (MKT-1e2) — the order is theirs by proof — or null.
     */
    public MarketplaceOrderDTOs.OrderView checkout(MarketplaceOrderDTOs.CheckoutRequest req, Long customerId) {
        Contact c = validate(req);
        boolean card = CARD.equals(c.paymentMode());
        if (card && customerId == null) throw new ValidationException("Sign in to pay online.");
        if (card && (req.cardToken() == null || req.cardToken().isBlank() || req.cardToken().length() > 200))
            throw new ValidationException("Enter your card details.");

        MarketplaceOrder replay = orders.findByIdempotencyKey(c.key()).orElse(null);
        if (replay != null) return view(replay);                     // a double submit is the same order

        if (orders.countByCustomerPhoneAndStatus(c.phone(), Order.SUBMITTED.name()) >= MAX_OPEN_PER_PHONE)
            throw new ValidationException("You already have " + MAX_OPEN_PER_PHONE
                    + " orders waiting for sellers to confirm. Please wait for an answer first.");

        MarketplaceOffer offer = offers.findById(req.offerId()).orElse(null);
        MarketplaceOfferProjection row = offer == null ? null : projections.findById(offer.getId()).orElse(null);
        MarketplaceProduct product = offer == null ? null : products.findById(offer.getMktProductId()).orElse(null);
        if (offer == null || row == null || product == null || !PublicOfferService.visible(product)
                || !MarketplaceOfferProjection.LIVE.equals(row.getStatus()))
            throw new ValidationException("This offer is no longer available. Please choose another offer.");

        // the SAME eligibility the catalogue showed (city, quantity, price limits, stale stock, regulated)
        if (publicOffers.eligible(product, List.of(row), c.city(), BigDecimal.valueOf(c.quantity()), null).isEmpty())
            throw new ValidationException("This seller cannot deliver " + c.quantity() + " to " + c.city()
                    + " right now. Please choose another offer.");

        BigDecimal price = row.getPrice().setScale(2, RoundingMode.HALF_UP);
        if (req.expectedPrice() == null || price.compareTo(req.expectedPrice().setScale(2, RoundingMode.HALF_UP)) != 0)
            throw new ValidationException("The price changed to Rs. " + String.format(Locale.ROOT, "%,.0f", price)
                    + ". Please review and place the order again.");

        rule(() -> MarketplaceCatalogService.PHASE.checkCheckout(List.of(new PhaseGuard.CheckoutLine(offer.getId(),
                offer.getSellerOrganizationId(), StockSourceType.valueOf(offer.getStockSourceType()),
                Regulated.valueOf(product.getRegulatedStatus())))));
        if (!card && !shippingPolicy.codEnabled(offer.getSellerOrganizationId()))   // cash only: a card needs no seller cash
            throw new ValidationException("This seller does not accept cash on delivery yet. Please choose another offer.");

        // ── tx 1: the order, its seller order, the snapshot, the number ──
        Long[] ids;
        try {
            ids = tx().execute(s -> create(c, offer, product, price, customerId));
        } catch (DataIntegrityViolationException race) {
            // two submits with the same key arrived together: UNIQUE(idempotency_key) let one through
            return orders.findByIdempotencyKey(c.key()).map(this::view).orElseThrow(() -> race);
        }
        Long orderId = ids[0], sellerOrderId = ids[1];

        // ── remote: hold the stock as the seller (outside any transaction) ──
        MarketplaceSellerOrder so = sellerOrders.findById(sellerOrderId).orElseThrow();
        String refusal = hold(so, offer.getSourceProductId(), c.quantity());

        // ── remote: the card, AFTER the stock is held (never charge for stock that cannot be held) ──
        MarketplacePaymentService.Outcome paid = refusal != null || !card ? null
                : payments.charge(orders.findById(orderId).orElseThrow(), req.cardToken());

        // ── tx 2: record the answer ──
        tx().executeWithoutResult(s -> {
            MarketplaceSellerOrder fresh = sellerOrders.findById(sellerOrderId).orElseThrow();
            MarketplaceOrder order = orders.findById(orderId).orElseThrow();
            if (refusal != null) {
                move(fresh, SellerOrder.CANCELLED);
                cancel(order, "This seller no longer has enough stock. Please choose another offer.");
            } else if (paid == MarketplacePaymentService.Outcome.FAILED) {
                move(fresh, SellerOrder.CANCELLED);
                fresh.setHeld(true);                                   // the release below gives the stock back
                cancel(order, DECLINED);
            } else if (paid == MarketplacePaymentService.Outcome.UNKNOWN) {
                // never offered to a seller on an unconfirmed payment: the orphan rule cancels it and, if the provider
                // later reports the charge, the refund backstop gives the money back
                rule(() -> MarketplaceStateMachines.ORDER.transition(Order.valueOf(order.getStatus()), Order.PAYMENT_PENDING));
                order.setStatus(Order.PAYMENT_PENDING.name());
                fresh.setHeld(true);
            } else {
                move(fresh, SellerOrder.OFFERED);
                fresh.setHeld(true);
                fresh.setAcceptBy(LocalDateTime.now().plusMinutes(settings.acceptMinutes()));
            }
            sellerOrders.save(fresh);
            orders.save(order);
        });
        if (refusal != null) {
            LOG.info("MKT checkout {} not held: {}", c.key(), refusal);
            throw new ValidationException("This seller no longer has enough stock. Please choose another offer.");
        }
        if (paid == MarketplacePaymentService.Outcome.FAILED) {
            sellerSide.getObject().release(sellerOrders.findById(sellerOrderId).orElseThrow());
            throw new ValidationException(DECLINED);
        }
        return view(orders.findById(orderId).orElseThrow());
    }

    private Long[] create(Contact c, MarketplaceOffer offer, MarketplaceProduct product, BigDecimal price, Long customerId) {
        BigDecimal subtotal = price.multiply(BigDecimal.valueOf(c.quantity()));
        MarketplaceOrder o = new MarketplaceOrder();
        o.setIdempotencyKey(c.key());
        o.setStatus(Order.SUBMITTED.name());
        o.setPaymentMode(c.paymentMode());
        o.setCustomerId(customerId);
        o.setPaymentStatus(Payment.UNPAID.name());
        o.setCustomerName(c.name());
        o.setCustomerPhone(c.phone());
        o.setDeliveryAddress(c.address());
        o.setCity(c.city());
        o.setSubtotal(subtotal);
        o.setDeliveryFee(BigDecimal.ZERO);           // Phase 1: delivery is in the seller's price (design §6.2, MKT-1e §5)
        o.setTotal(subtotal);
        // allocate LATE: immediately before the insert that needs it, after every check and every remote call
        o.setOrderNo(String.format(Locale.ROOT, "MKT-%06d", numbers.next(PLATFORM_ORG, DOC_TYPE)));
        o = orders.saveAndFlush(o);

        MarketplaceSellerOrder so = new MarketplaceSellerOrder();
        so.setMktOrderId(o.getId());
        so.setSellerOrganizationId(offer.getSellerOrganizationId());
        so.setAcceptanceStatus(SellerOrder.UNASSIGNED.name());
        so.setHoldKey("MKT-" + java.util.UUID.randomUUID());
        so.setHeld(false);
        so = sellerOrders.saveAndFlush(so);

        lines.save(snapshot(so, offer, product, price, c.quantity()));
        return new Long[] {o.getId(), so.getId()};
    }

    /** The order-time copy of everything that could change later (source §13.3). */
    private MarketplaceOrderLine snapshot(MarketplaceSellerOrder so, MarketplaceOffer offer, MarketplaceProduct product,
            BigDecimal price, int qty) {
        MarketplaceOrderLine l = new MarketplaceOrderLine();
        l.setSellerOrderId(so.getId());
        l.setOfferId(offer.getId());
        l.setMktProductId(product.getId());
        l.setSourceProductId(offer.getSourceProductId());
        l.setProductName(product.getCanonicalName());
        l.setQuantity(qty);
        l.setUnitPrice(price);
        l.setLineTotal(price.multiply(BigDecimal.valueOf(qty)));
        l.setStockSourceType(offer.getStockSourceType());
        l.setSellerOrganizationId(offer.getSellerOrganizationId());
        l.setStockOwnerOrganizationId(offer.getStockOwnerOrganizationId());
        l.setCustodianOrganizationId(offer.getCustodianOrganizationId());
        l.setFulfillerOrganizationId(offer.getFulfillerOrganizationId());
        l.setPromiseHours(offer.getPromiseHours());
        MarketplacePolicy w = offer.getWarrantyPolicyId() == null ? null : policies.findById(offer.getWarrantyPolicyId()).orElse(null);
        if (w != null) {
            l.setWarrantyPolicyId(w.getId());
            l.setWarrantyProvider(w.getWarrantyProvider());
            l.setWarrantyMonths(w.getWarrantyMonths());
            l.setWarrantyStarts(w.getWarrantyStarts());
            l.setWarrantyCovers(w.getWarrantyCovers());
            l.setWarrantyExcludes(w.getWarrantyExcludes());
        }
        MarketplacePolicy r = offer.getReturnPolicyId() == null ? null : policies.findById(offer.getReturnPolicyId()).orElse(null);
        if (r != null) {
            l.setReturnPolicyId(r.getId());
            l.setReturnDays(r.getReturnDays());
        }
        MarketplacePolicy cm = offer.getCommissionPolicyId() == null ? null : policies.findById(offer.getCommissionPolicyId()).orElse(null);
        if (cm != null) {
            l.setCommissionPolicyId(cm.getId());
            l.setCommissionBasis(cm.getCommissionBasis());
            l.setCommissionRate(cm.getCommissionRate());
            l.setCommissionFixed(cm.getCommissionFixed());
        }
        l.setSettlementStatus(Settlement.NOT_ELIGIBLE.name());
        return l;
    }

    /** @return null when held, otherwise why not. Never throws: an outage reads as "not held", never as "held". */
    String hold(MarketplaceSellerOrder so, Long sourceProductId, int qty) {
        try {
            StockHoldResponse r = AsOrg.call(so.getSellerOrganizationId(), () -> trade.holdStock(StockHoldRequest.builder()
                    .organizationId(so.getSellerOrganizationId())
                    .holdKey(so.getHoldKey())
                    .lines(List.of(new StockReservationLine(sourceProductId, BigDecimal.valueOf(qty))))
                    .build()));
            if (r != null && r.isHeld()) return null;
            return r == null ? "inventory did not answer" : r.getReason();
        } catch (RuntimeException e) {
            LOG.warn("MKT hold {} failed: {}", so.getHoldKey(), e.toString());
            return "the stock could not be checked";
        }
    }

    // ── tracking ───────────────────────────────────────────────────────────────────────────────────────

    /** Anonymous. The order number AND the phone it was placed with; anything else reads "No such order." */
    @Transactional(readOnly = true)
    public MarketplaceOrderDTOs.OrderView track(String orderNo, String phone) {
        MarketplaceOrder o = orderNo == null ? null : orders.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (o == null || phone == null || !digits(o.getCustomerPhone()).equals(digits(phone)))
            throw new ResourceNotFoundException("No such order.");
        return view(o);
    }

    // ── operator ───────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<MarketplaceOrderDTOs.OrderView> operatorOrders(String status, Integer page, Integer size) {
        access.assertOperator();
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 50 : Math.min(size, 100));
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null) {
            try {
                Order.valueOf(st);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Unknown order status: " + status);
            }
        }
        return PageResponse.of(st == null ? orders.findAllByOrderByCreatedAtDesc(p) : orders.findByStatusOrderByCreatedAtDesc(st, p),
                this::view);
    }

    // ── shared ─────────────────────────────────────────────────────────────────────────────────────────

    /** The shopper's view: the order, its (Phase 1: one) seller order, and the snapshot lines without commission. */
    MarketplaceOrderDTOs.OrderView view(MarketplaceOrder o) {
        MarketplaceSellerOrder so = sellerOrders.findByMktOrderId(o.getId()).stream().findFirst().orElse(null);
        List<MarketplaceOrderDTOs.LineView> ls = so == null ? List.of()
                : lines.findBySellerOrderIdOrderByIdAsc(so.getId()).stream().map(MarketplaceCheckoutService::lineView).toList();
        String seller = so == null ? null : sellerAccounts.findByOrganizationId(so.getSellerOrganizationId())
                .map(a -> a.getDisplayName()).orElse(null);
        return new MarketplaceOrderDTOs.OrderView(o.getOrderNo(), o.getStatus(), o.getPaymentMode(), o.getPaymentStatus(),
                o.getSubtotal(), o.getDeliveryFee(), o.getTotal(), o.getCancelReason(), o.getCreatedAt(),
                so == null ? null : so.getId(), so == null ? null : so.getVersion(),
                so == null ? null : so.getAcceptanceStatus(), seller, secondsLeft(so), o.getCity(), ls);
    }

    static MarketplaceOrderDTOs.LineView lineView(MarketplaceOrderLine l) {
        return new MarketplaceOrderDTOs.LineView(l.getId(), l.getOfferId(), l.getMktProductId(), l.getProductName(), l.getQuantity(),
                l.getUnitPrice(), l.getLineTotal(), l.getStockSourceType(), l.getSellerOrganizationId(),
                l.getStockOwnerOrganizationId(), l.getCustodianOrganizationId(), l.getFulfillerOrganizationId(),
                l.getPromiseHours(), l.getWarrantyProvider(), l.getWarrantyMonths(), l.getWarrantyStarts(),
                l.getWarrantyCovers(), l.getWarrantyExcludes(), l.getReturnDays());
    }

    /** Seconds the seller has left, computed HERE (the server's clock wrote accept_by). Null unless OFFERED. */
    static Long secondsLeft(MarketplaceSellerOrder so) {
        if (so == null || so.getAcceptBy() == null || !SellerOrder.OFFERED.name().equals(so.getAcceptanceStatus())) return null;
        return Math.max(0, Duration.between(LocalDateTime.now(), so.getAcceptBy()).getSeconds());
    }

    static void move(MarketplaceSellerOrder so, SellerOrder to) {
        rule(() -> MarketplaceStateMachines.SELLER_ORDER.transition(SellerOrder.valueOf(so.getAcceptanceStatus()), to));
        so.setAcceptanceStatus(to.name());
    }

    static void cancel(MarketplaceOrder o, String reason) {
        rule(() -> MarketplaceStateMachines.ORDER.transition(Order.valueOf(o.getStatus()), Order.CANCELLED));
        o.setStatus(Order.CANCELLED.name());
        o.setCancelReason(reason);
    }

    static void rule(Runnable r) {
        try {
            r.run();
        } catch (MarketplaceRuleException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ── input ──────────────────────────────────────────────────────────────────────────────────────────

    record Contact(String key, String name, String phone, String address, String city, int quantity, String paymentMode) {
    }

    static Contact validate(MarketplaceOrderDTOs.CheckoutRequest req) {
        if (req == null || req.offerId() == null) throw new ValidationException("Choose an offer first.");
        String key = trim(req.idempotencyKey());
        if (key == null || key.length() > 80) throw new ValidationException("Please reload the page and try again.");
        int qty = req.quantity() == null ? 1 : req.quantity();
        if (qty < 1 || qty > MAX_QUANTITY) throw new ValidationException("You can order 1 to " + MAX_QUANTITY + " at a time.");
        String name = trim(req.customerName());
        if (name == null || name.length() < 2 || name.length() > 120) throw new ValidationException("Enter your name.");
        String phone = trim(req.customerPhone());
        String d = digits(phone);
        if (phone == null || phone.length() > 32 || d.length() < 7 || d.length() > 15 || !phone.matches("[0-9+()\\-\\s]+"))
            throw new ValidationException("Enter a phone number the seller can call.");
        String address = trim(req.address());
        if (address == null || address.length() < 5 || address.length() > 300)
            throw new ValidationException("Enter the delivery address.");
        String city = trim(req.city());
        if (city == null || city.length() < 2 || city.length() > 60) throw new ValidationException("Enter the delivery city.");
        String mode = req.paymentMode() == null || req.paymentMode().isBlank() ? COD : req.paymentMode().trim().toUpperCase(Locale.ROOT);
        if (!COD.equals(mode) && !CARD.equals(mode)) throw new ValidationException("Choose cash on delivery or pay online.");
        return new Contact(key, name, d, address, city, qty, mode);   // phone stored as digits: one shopper, one key
    }

    static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
