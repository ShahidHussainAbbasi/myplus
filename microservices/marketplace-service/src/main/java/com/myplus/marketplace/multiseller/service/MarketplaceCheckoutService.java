package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus;
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
 * MKT-1e — the shopper's side: checkout and tracking. MKT-2a: one checkout may buy from several sellers.
 * Contracts: docs/slices/mkt-1e-checkout-acceptance.md, docs/slices/mkt-2a-multi-seller-orders.md
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
    /** MKT-2b: what became of a part its seller could not fulfil, shown on the shopper's view. */
    private final com.myplus.marketplace.multiseller.repository.MarketplaceShortageRepository shortages;
    /** The seller side releases holds; it also calls {@link #hold}, so it is resolved on demand (a real cycle). */
    private final org.springframework.beans.factory.ObjectProvider<SellerOrderService> sellerSide;
    /** MKT-2c: every seller asked at once, each with a timeout, the checkout with one deadline, circuits per seller. */
    private final LiveRouting routing;
    private final CodStandingService codStanding;

    // ── checkout ───────────────────────────────────────────────────────────────────────────────────────

    /** Anonymous (MKT-1e). */
    public MarketplaceOrderDTOs.OrderView checkout(MarketplaceOrderDTOs.CheckoutRequest req) {
        return checkout(req, null);
    }

    /** One basket line after every check: the offer, its live row and product, the server's price, the quantity. */
    record Item(MarketplaceOffer offer, MarketplaceOfferProjection row, MarketplaceProduct product, BigDecimal price, int qty) {
        BigDecimal total() {
            return price.multiply(BigDecimal.valueOf(qty));
        }
    }

    /**
     * Not {@code @Transactional}: it runs its own two transactions with the remote holds (and, for a card, the charge)
     * between them. {@code customerId}: the signed-in account (MKT-1e2) — the order is theirs by proof — or null.
     *
     * <h3>MKT-2a: several sellers</h3>
     * The basket is split into one seller order (a "part") per seller, each holding all of its own lines under its own
     * key. All or nothing at placement: if any part cannot be held, every part is cancelled and every hold released,
     * and the shopper is told which seller could not. A card is charged ONCE, for the whole total, after every part is
     * held. From there each part lives on its own (accept, reject, expire, refund, support); the parent follows its
     * parts ({@link #follow}).
     */
    public MarketplaceOrderDTOs.OrderView checkout(MarketplaceOrderDTOs.CheckoutRequest req, Long customerId) {
        Contact c = validate(req);
        List<Want> wants = wants(req);
        boolean card = CARD.equals(c.paymentMode());
        if (card && customerId == null) throw new ValidationException("Sign in to pay online.");
        if (card && (req.cardToken() == null || req.cardToken().isBlank() || req.cardToken().length() > 200))
            throw new ValidationException("Enter your card details.");

        MarketplaceOrder replay = orders.findByIdempotencyKey(c.key()).orElse(null);
        if (replay != null) return view(replay);                     // a double submit is the same order

        if (orders.countByCustomerPhoneAndStatus(c.phone(), Order.SUBMITTED.name()) >= MAX_OPEN_PER_PHONE)
            throw new ValidationException("You already have " + MAX_OPEN_PER_PHONE
                    + " orders waiting for sellers to confirm. Please wait for an answer first.");

        boolean basket = wants.size() > 1;
        List<Item> items = new ArrayList<>();
        for (Want w : wants) items.add(item(w, c.city(), basket));

        boolean multiSeller = settings.multiSeller();
        rule(() -> MarketplaceCatalogService.PHASE.checkCheckout(items.stream().map(i -> new PhaseGuard.CheckoutLine(
                i.offer().getId(), i.offer().getSellerOrganizationId(), StockSourceType.valueOf(i.offer().getStockSourceType()),
                Regulated.valueOf(i.product().getRegulatedStatus()))).toList(), multiSeller, settings.platformStock()));

        // one part per seller, in the order the shopper added them
        Map<Long, List<Item>> bySeller = new LinkedHashMap<>();
        for (Item i : items) bySeller.computeIfAbsent(i.offer().getSellerOrganizationId(), k -> new ArrayList<>()).add(i);
        if (!card) {                                                   // cash only: a card needs no seller cash
            for (Long seller : bySeller.keySet())
                if (!shippingPolicy.codEnabled(seller))
                    throw new ValidationException(bySeller.size() == 1
                            ? "This seller does not accept cash on delivery yet. Please choose another offer."
                            : sellerName(seller) + " does not accept cash on delivery yet. Please remove its items or pay online.");
            for (Long seller : bySeller.keySet())                     // MKT-2d: only when the operator switched it on
                if (codStanding.codStopped(seller))
                    throw new ValidationException(bySeller.size() == 1
                            ? "This seller cannot take cash on delivery right now. Please pay online or choose another offer."
                            : sellerName(seller) + " cannot take cash on delivery right now. Please remove its items or pay online.");
        }

        // ── tx 1: the order, one seller order per seller, the snapshots, the number ──
        Long orderId;
        try {
            orderId = tx().execute(s -> create(c, bySeller, customerId));
        } catch (DataIntegrityViolationException race) {
            // two submits with the same key arrived together: UNIQUE(idempotency_key) let one through
            return orders.findByIdempotencyKey(c.key()).map(this::view).orElseThrow(() -> race);
        }
        List<MarketplaceSellerOrder> parts = sellerOrders.findByMktOrderId(orderId);

        // ── remote: hold every part's stock as its seller, all at once, outside any transaction (MKT-2c: each seller
        //    has its own timeout and the whole checkout one deadline; never wait on a seller past them) ──
        List<LiveRouting.Answer> answers = routing.holdAll(parts.stream()
                .map(so -> ask(so, lines.findBySellerOrderIdOrderByIdAsc(so.getId()))).toList(), routing.deadlineFromNow());
        Long refusedSeller = null;
        boolean silent = false;
        for (int i = 0; i < parts.size(); i++) {
            LiveRouting.Answer a = answers.get(i);
            if (a.held()) continue;
            LOG.info("MKT checkout {} part {} not held: {} ({})", c.key(), parts.get(i).getId(), a.kind(), a.reason());
            if (refusedSeller == null) {                                    // the first one the shopper added
                refusedSeller = parts.get(i).getSellerOrganizationId();
                silent = a.silent();
            }
        }
        final Long refused = refusedSeller;
        String refusedFor = refused == null ? null : refusal(refused, silent, bySeller.size() == 1);

        // ── remote: the card, AFTER every part is held (never charge for stock that cannot be held) ──
        MarketplacePaymentService.Outcome paid = refused != null || !card ? null
                : payments.charge(orders.findById(orderId).orElseThrow(), req.cardToken());

        // ── tx 2: record the answer, for every part ──
        tx().executeWithoutResult(s -> {
            MarketplaceOrder order = orders.findById(orderId).orElseThrow();
            List<MarketplaceSellerOrder> fresh = sellerOrders.findByMktOrderId(orderId);
            if (refused != null || paid == MarketplacePaymentService.Outcome.FAILED) {
                for (MarketplaceSellerOrder so : fresh) {
                    move(so, SellerOrder.CANCELLED);
                    so.setHeld(true);                                   // the release below gives back whatever was held
                }
                cancel(order, refused != null ? refusedFor : DECLINED);
            } else if (paid == MarketplacePaymentService.Outcome.UNKNOWN) {
                // never offered to a seller on an unconfirmed payment: the orphan rule cancels it and, if the provider
                // later reports the charge, the refund backstop gives the money back
                rule(() -> MarketplaceStateMachines.ORDER.transition(Order.valueOf(order.getStatus()), Order.PAYMENT_PENDING));
                order.setStatus(Order.PAYMENT_PENDING.name());
                fresh.forEach(so -> so.setHeld(true));
            } else {
                LocalDateTime now = LocalDateTime.now();
                for (MarketplaceSellerOrder so : fresh) {
                    move(so, SellerOrder.OFFERED);
                    so.setHeld(true);
                    // MKT-2-06: each seller's window follows the value of ITS part, not the whole basket
                    so.setAcceptBy(now.plusMinutes(settings.acceptMinutesFor(so.getSellerOrganizationId(),
                            partTotal(lines.findBySellerOrderIdOrderByIdAsc(so.getId())))));
                }
            }
            fresh.forEach(sellerOrders::save);
            orders.save(order);
        });
        if (refused != null || paid == MarketplacePaymentService.Outcome.FAILED) {
            // after the commit; by key, so a part that was never held is a harmless no-op
            for (MarketplaceSellerOrder so : sellerOrders.findByMktOrderId(orderId)) sellerSide.getObject().release(so);
            throw new ValidationException(refused != null ? refusedFor : DECLINED);
        }
        return view(orders.findById(orderId).orElseThrow());
    }

    /** One basket line checked exactly as the catalogue showed it; the messages name the product when there are several. */
    private Item item(Want w, String city, boolean basket) {
        MarketplaceOffer offer = offers.findById(w.offerId()).orElse(null);
        MarketplaceOfferProjection row = offer == null ? null : projections.findById(offer.getId()).orElse(null);
        MarketplaceProduct product = offer == null ? null : products.findById(offer.getMktProductId()).orElse(null);
        if (offer == null || row == null || product == null || !PublicOfferService.visible(product)
                || !MarketplaceOfferProjection.LIVE.equals(row.getStatus()))
            throw new ValidationException(basket
                    ? "An offer in your basket is no longer available. Please remove it and choose another."
                    : "This offer is no longer available. Please choose another offer.");
        String what = basket ? " (" + product.getCanonicalName() + ")" : "";

        // the SAME eligibility the catalogue showed (city, quantity, price limits, stale stock, regulated)
        if (publicOffers.eligible(product, List.of(row), city, BigDecimal.valueOf(w.qty()), null).isEmpty())
            throw new ValidationException(basket
                    ? sellerName(offer.getSellerOrganizationId()) + " cannot deliver " + w.qty() + what + " to " + city
                            + " right now. Please remove it from your basket."
                    : "This seller cannot deliver " + w.qty() + " to " + city + " right now. Please choose another offer.");

        BigDecimal price = row.getPrice().setScale(2, RoundingMode.HALF_UP);
        if (w.expectedPrice() == null || price.compareTo(w.expectedPrice().setScale(2, RoundingMode.HALF_UP)) != 0)
            throw new ValidationException("The price" + (basket ? " of " + product.getCanonicalName() : "") + " changed to Rs. "
                    + String.format(Locale.ROOT, "%,.0f", price) + ". Please review and place the order again.");
        return new Item(offer, row, product, price, w.qty());
    }

    String sellerName(Long org) {
        if (settings.warehouseOrg().filter(org::equals).isPresent()) return PlatformWarehouseService.NAME;   // MKT-3a
        return sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName()).filter(n -> n != null && !n.isBlank())
                .orElse("A seller");
    }

    private Long create(Contact c, Map<Long, List<Item>> bySeller, Long customerId) {
        BigDecimal subtotal = bySeller.values().stream().flatMap(List::stream).map(Item::total).reduce(BigDecimal.ZERO, BigDecimal::add);
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

        for (Map.Entry<Long, List<Item>> part : bySeller.entrySet()) {
            MarketplaceSellerOrder so = new MarketplaceSellerOrder();
            so.setMktOrderId(o.getId());
            so.setSellerOrganizationId(part.getKey());
            so.setAcceptanceStatus(SellerOrder.UNASSIGNED.name());
            so.setHoldKey("MKT-" + java.util.UUID.randomUUID());
            so.setHeld(false);
            so = sellerOrders.saveAndFlush(so);
            for (Item i : part.getValue()) lines.save(snapshot(so, i.offer(), i.product(), i.price(), i.qty()));
        }
        return o.getId();
    }

    /** The order-time copy of everything that could change later (source §13.3). */
    MarketplaceOrderLine snapshot(MarketplaceSellerOrder so, MarketplaceOffer offer, MarketplaceProduct product,
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

    /**
     * What the shopper is told when a part could not be held (R18.5: never a silent confirm). A seller that did not
     * answer in time is named as such, so the shopper knows another offer may work where "no stock" would not.
     */
    String refusal(Long seller, boolean silent, boolean oneSeller) {
        if (silent) return oneSeller
                ? "This seller did not answer in time. Please choose another offer."
                : sellerName(seller) + " did not answer in time. Please remove its items and place the order again.";
        return oneSeller
                ? "This seller no longer has enough stock. Please choose another offer."
                : sellerName(seller) + " no longer has enough stock. Please remove its items and place the order again.";
    }

    /** MKT-2c: one part's hold as a routed call: as its seller, under the part's own key. */
    LiveRouting.Ask ask(MarketplaceSellerOrder so, List<MarketplaceOrderLine> partLines) {
        return new LiveRouting.Ask(so.getSellerOrganizationId(), so.getHoldKey(), holdRequest(so, partLines));
    }

    /** MKT-2c, for a reroute (MKT-2b): one candidate seller asked like a checkout part, within {@code until}. */
    LiveRouting.Answer route(MarketplaceSellerOrder so, List<MarketplaceOrderLine> partLines, long until) {
        return routing.hold(ask(so, partLines), until);
    }

    /** MKT-2c: the overall deadline for one routing decision, starting now. */
    long routingDeadline() {
        return routing.deadlineFromNow();
    }

    /** Every line of one part under the part's key. A product on two lines is held once, for both quantities. */
    static StockHoldRequest holdRequest(MarketplaceSellerOrder so, List<MarketplaceOrderLine> partLines) {
        Map<Long, BigDecimal> qty = new LinkedHashMap<>();
        for (MarketplaceOrderLine l : partLines) qty.merge(l.getSourceProductId(), BigDecimal.valueOf(l.getQuantity()), BigDecimal::add);
        return StockHoldRequest.builder()
                .organizationId(so.getSellerOrganizationId())
                .holdKey(so.getHoldKey())
                .lines(qty.entrySet().stream().map(e -> new StockReservationLine(e.getKey(), e.getValue())).toList())
                .build();
    }

    /**
     * Hold every line of one part under the part's key, waiting as long as it takes: the seller's own re-hold after a
     * failed sale ({@link SellerOrderService#accept}), which is not routing. Checkout and a reroute use {@link #ask}.
     * @return null when held, otherwise why not. Never throws: an outage reads as "not held", never as "held".
     */
    String hold(MarketplaceSellerOrder so, List<MarketplaceOrderLine> partLines) {
        StockHoldRequest req = holdRequest(so, partLines);
        if (req.getLines().isEmpty()) return "nothing to hold";
        try {
            StockHoldResponse r = AsOrg.call(so.getSellerOrganizationId(), () -> trade.holdStock(req));
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

    /**
     * The shopper's view: the order, its parts (one per seller) and the snapshot lines without commission. The single
     * {@code sellerOrder*} fields describe the first part, as before MKT-2a; {@code sellerName} names every seller.
     */
    MarketplaceOrderDTOs.OrderView view(MarketplaceOrder o) {
        List<MarketplaceOrderDTOs.PartView> parts = parts(o);
        MarketplaceOrderDTOs.PartView first = parts.isEmpty() ? null : parts.get(0);
        List<MarketplaceOrderDTOs.LineView> ls = parts.stream().flatMap(p -> p.lines().stream()).toList();
        String sellers = parts.isEmpty() ? null : String.join(", ", parts.stream().map(MarketplaceOrderDTOs.PartView::sellerName)
                .filter(java.util.Objects::nonNull).distinct().toList());
        Long secondsLeft = parts.stream().map(MarketplaceOrderDTOs.PartView::secondsToAccept).filter(java.util.Objects::nonNull)
                .max(Long::compare).orElse(null);
        return new MarketplaceOrderDTOs.OrderView(o.getOrderNo(), o.getStatus(), o.getPaymentMode(), o.getPaymentStatus(),
                o.getSubtotal(), o.getDeliveryFee(), o.getTotal(), o.getCancelReason(), o.getCreatedAt(),
                first == null ? null : first.id(), first == null ? null : first.version(),
                first == null ? null : first.status(), sellers == null || sellers.isEmpty() ? null : sellers, secondsLeft,
                o.getCity(), ls, parts);
    }

    /** MKT-2a — each seller's part, oldest first. */
    List<MarketplaceOrderDTOs.PartView> parts(MarketplaceOrder o) {
        List<MarketplaceSellerOrder> sos = sellerOrders.findByMktOrderId(o.getId()).stream()
                .sorted(java.util.Comparator.comparing(MarketplaceSellerOrder::getId)).toList();
        Map<Long, com.myplus.marketplace.multiseller.entity.MarketplaceShortage> shortBy = new java.util.HashMap<>();
        for (var sh : shortages.findByMktOrderIdOrderByIdAsc(o.getId())) shortBy.put(sh.getSellerOrderId(), sh);
        List<MarketplaceOrderDTOs.PartView> out = new ArrayList<>();
        for (MarketplaceSellerOrder so : sos) {
            List<MarketplaceOrderLine> ls = lines.findBySellerOrderIdOrderByIdAsc(so.getId());
            BigDecimal subtotal = partTotal(ls);
            int promise = ls.stream().map(MarketplaceOrderLine::getPromiseHours).filter(java.util.Objects::nonNull)
                    .max(Integer::compare).orElse(0);
            LocalDateTime from = so.getDecidedAt() != null && SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus())
                    ? so.getDecidedAt() : so.getCreatedAt();
            out.add(new MarketplaceOrderDTOs.PartView(so.getId(), so.getVersion(), so.getSellerOrganizationId(),
                    sellerName(so.getSellerOrganizationId()),
                    so.getAcceptanceStatus(), secondsLeft(so), subtotal, BigDecimal.ZERO, subtotal,
                    from == null || promise == 0 ? null : from.plusHours(promise), so.getDeliveredAt(),
                    ls.stream().map(MarketplaceCheckoutService::lineView).toList(), shortageView(shortBy.get(so.getId()), subtotal)));
        }
        return out;
    }

    /**
     * MKT-2b — the shopper's view of a short part: who it moved to, or the alternative they are asked about. The
     * cause and party are the seller's and the operator's business, never shown here.
     */
    MarketplaceOrderDTOs.ShortageView shortageView(com.myplus.marketplace.multiseller.entity.MarketplaceShortage sh, BigDecimal partTotal) {
        // the shopper hears of a shortage only when another seller was looked for; a cause alone is between the
        // seller and MaxTheService (the order then reads exactly as before MKT-2b)
        if (sh == null || (sh.getAttempts() == 0 && !com.myplus.marketplace.multiseller.entity.MarketplaceShortage.PENDING.equals(sh.getResult())))
            return null;
        MarketplaceSellerOrder moved = sh.getReplacementSellerOrderId() == null ? null
                : sellerOrders.findById(sh.getReplacementSellerOrderId()).orElse(null);
        String movedTo = moved == null ? null : sellerName(moved.getSellerOrganizationId());
        // what the shopper pays now against what they paid: the new part once moved, the alternative while asked
        BigDecimal now = moved != null ? partTotal(lines.findBySellerOrderIdOrderByIdAsc(moved.getId())) : sh.getProposalTotal();
        boolean asking = MarketplaceStatus.ShortageResult.SUBSTITUTION_REQUESTED.name().equals(sh.getResult());
        Long toDecide = !asking || sh.getProposalExpiresAt() == null ? null
                : Math.max(0, Duration.between(LocalDateTime.now(), sh.getProposalExpiresAt()).getSeconds());
        return new MarketplaceOrderDTOs.ShortageView(sh.getId(), sh.getResult(), movedTo,
                sh.getProposalSellerOrgId() == null ? null : sellerName(sh.getProposalSellerOrgId()), sh.getProposalTotal(),
                sh.getProposalPromiseHours(), toDecide,
                now == null ? null : now.subtract(partTotal), sh.getCustomerDecision());
    }

    /** What one part costs the shopper. Phase 1: delivery is in the seller's price, so it is the sum of its lines. */
    static BigDecimal partTotal(List<MarketplaceOrderLine> ls) {
        return ls.stream().map(MarketplaceOrderLine::getLineTotal).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static final java.util.Set<String> LIVE_PARTS = java.util.Set.of(SellerOrder.UNASSIGNED.name(), SellerOrder.OFFERED.name());
    static final java.util.Set<String> ACCEPTED_PARTS = java.util.Set.of(SellerOrder.ACCEPTED.name(), SellerOrder.HANDED_OVER.name());

    /**
     * MKT-2a — the parent follows its parts, inside the caller's transaction, after one part moved:
     * <ul>
     *   <li>a part accepted → the order is CONFIRMED (once; a second acceptance changes nothing);</li>
     *   <li>no part accepted and none still waiting → the order is CANCELLED with {@code reasonIfEnded};</li>
     *   <li>otherwise (a part still waiting, or a short part being moved to another seller — MKT-2b) → unchanged.</li>
     * </ul>
     * A cancelled order is never revived. With one part this is exactly MKT-1e's rule.
     */
    static void follow(MarketplaceOrder parent, List<MarketplaceSellerOrder> parts, String reasonIfEnded) {
        if (Order.CANCELLED.name().equals(parent.getStatus())) return;
        boolean accepted = parts.stream().anyMatch(p -> ACCEPTED_PARTS.contains(p.getAcceptanceStatus()));
        // MKT-2b: a part whose shortage is still being resolved (another seller looked for, or the shopper asked) waits too
        boolean waiting = parts.stream().anyMatch(p -> LIVE_PARTS.contains(p.getAcceptanceStatus())
                || Boolean.TRUE.equals(p.getShortagePending()));
        if (accepted) {
            if (!Order.CONFIRMED.name().equals(parent.getStatus())) {
                rule(() -> MarketplaceStateMachines.ORDER.transition(Order.valueOf(parent.getStatus()), Order.CONFIRMED));
                parent.setStatus(Order.CONFIRMED.name());
            }
        } else if (!waiting) {
            cancel(parent, reasonIfEnded);
        }
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

    /** One basket line as asked for (before any check against the catalogue). */
    record Want(Long offerId, int qty, BigDecimal expectedPrice) {
    }

    /** At most this many different offers in one basket. */
    static final int MAX_LINES = 10;

    /**
     * MKT-2a — the basket: {@code lines} when sent, otherwise the one offer of an MKT-1e request. The same offer twice
     * is one line with both quantities.
     */
    static List<Want> wants(MarketplaceOrderDTOs.CheckoutRequest req) {
        if (req.lines() == null || req.lines().isEmpty()) {
            int qty = req.quantity() == null ? 1 : req.quantity();
            return List.of(new Want(req.offerId(), qty, req.expectedPrice()));
        }
        Map<Long, Want> merged = new LinkedHashMap<>();
        for (MarketplaceOrderDTOs.CheckoutLine l : req.lines()) {
            if (l == null || l.offerId() == null) throw new ValidationException("Your basket has an item without an offer. Please reload it.");
            int qty = l.quantity() == null ? 1 : l.quantity();
            if (qty < 1 || qty > MAX_QUANTITY) throw new ValidationException("You can order 1 to " + MAX_QUANTITY + " of each item.");
            Want had = merged.get(l.offerId());
            if (had != null && (had.expectedPrice() == null || l.expectedPrice() == null
                    || had.expectedPrice().compareTo(l.expectedPrice()) != 0))
                throw new ValidationException("Your basket shows two prices for the same item. Please reload it.");
            merged.put(l.offerId(), new Want(l.offerId(), had == null ? qty : had.qty() + qty, l.expectedPrice()));
        }
        if (merged.size() > MAX_LINES) throw new ValidationException("A basket can hold up to " + MAX_LINES + " different items.");
        for (Want w : merged.values())
            if (w.qty() > MAX_QUANTITY) throw new ValidationException("You can order 1 to " + MAX_QUANTITY + " of each item.");
        return List.copyOf(merged.values());
    }

    static Contact validate(MarketplaceOrderDTOs.CheckoutRequest req) {
        boolean basket = req != null && req.lines() != null && !req.lines().isEmpty();
        if (req == null || (req.offerId() == null && !basket)) throw new ValidationException("Choose an offer first.");
        String key = trim(req.idempotencyKey());
        if (key == null || key.length() > 80) throw new ValidationException("Please reload the page and try again.");
        int qty = basket || req.quantity() == null ? 1 : req.quantity();
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
