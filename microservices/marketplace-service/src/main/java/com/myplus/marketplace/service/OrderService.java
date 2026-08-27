package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.commerce.contracts.dto.StockReturnLine;
import com.myplus.commerce.contracts.dto.StockReturnRequest;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.OrderTrackDTO;
import com.myplus.marketplace.dto.ShipmentDTO;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderItem;
import com.myplus.marketplace.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Order fulfilment (E1, slice 46). An order references the trade sale (invoiceNo); this tracks its fulfilment
 * lifecycle. org/user are passed in (controller reads CurrentUser) → unit-testable. Org-scoped.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    /** Synthetic actor for anonymous storefront reservations (org carries the tenant; stock is org-scoped). */
    private static final Long STOREFRONT_USER = 0L;

    private final OrderRepository repo;
    private final PaymentGateway paymentGateway;
    private final InventoryClient inventoryClient;   // O1: only the legacy (pre-invoice) cancel path still uses this
    private final com.myplus.commerce.contracts.client.TradeClient tradeClient;   // O1: the single revenue path
    private final OrderStockHoldService orderStockHoldService;   // O7 D1c: the confirmed order's stock promise
    private final NotificationService notificationService;
    private final com.myplus.marketplace.repository.OrderEventRepository orderEventRepository;
    private final com.myplus.marketplace.repository.StorefrontCustomerRepository customerRepo;
    private final CartService cartService;   // slice 68: close the persistent cart on successful checkout
    private final ShipmentService shipmentService;   // O5b: the parcels an order has gone out in
    /** O5c — one resolver shared with the quote, so what the shopper is told matches what is invoiced. */
    private final BackorderPolicy backorderPolicy;
    /** O7 D1 — who changed what on a booked order, and why (mandatory once TWO people may edit it). */
    private final com.myplus.marketplace.repository.OrderAmendmentRepository amendmentRepository;
    /**
     * O7 D1 — reads {@code order.booking.requireApproval}.
     *
     * <p>A constructor dependency, not {@code @Autowired(required = false)}. O3 shipped inert exactly that
     * way: the catalog and the resolver both existed, but with no {@code SettingsStore} bean the optional
     * injection quietly became "every store keeps the platform default" instead of a startup failure. A
     * service that means to be configurable must refuse to start unconfigurable.
     */
    private final com.myplus.common.settings.SettingsService settingsService;

    // ── OMS O7 D1 — distribution pre-sales: book → review → confirm/reject ────────────────────────────────

    /**
     * {@code booksStatus} for a confirmed order that has not been invoiced yet.
     *
     * <p>A FOURTH books state, and distinct for the reason O5c kept {@code BACKORDER_PENDING} out of
     * {@code LEGACY_UNPOSTED}: all three mean "no invoice", but this one is <b>correct and expected</b> — the
     * goods have not left the building, and under {@code ON_DISPATCH} the invoice is raised when they do.
     * Collapsing it into either of the others would bury a healthy order inside a real reconciliation backlog.
     */
    public static final String AWAITING_DISPATCH = "AWAITING_DISPATCH";

    /**
     * An order booker books an order at the outlet.
     *
     * <h3>Nothing is committed here — that is the whole point</h3>
     * No invoice, no stock movement, no money. A booker's order is a <b>request</b>, and the segregation of
     * duties that makes distribution auditable depends on the person who books not being the person who
     * releases.
     *
     * <p><b>Unless there is only one person.</b> {@code order.booking.requireApproval} (ON by default,
     * reproducing the behaviour this replaces) decides whether the order stops at {@code PENDING_APPROVAL} or
     * enters at {@code NEW}. A back office where the same human books and converts gains nothing from the
     * gate — it is one person clicking twice — and friction that protects nobody is friction people route
     * around. What survives the switch is the RECORD: the booker is still stamped, the timeline still logs
     * the transition, and the audit service still answers "who did this".
     *
     * <p>Under {@code ON_DISPATCH} (§6 D-1) the invoice is raised when the goods actually leave, from
     * the quantities that actually left — so an amendment here edits an order, never an issued invoice, and a
     * rejection voids nothing.
     *
     * <p>It still gets its {@code SO-} number immediately: the booker has to be able to quote a reference to
     * the shopkeeper before leaving the counter, and O5e already established that no kind of order should be
     * the one a merchant cannot track.
     */
    @Transactional
    public OrderDTO book(OrderDTO dto, Long orgId, Long userId) {
        return book(dto, orgId, userId, null);
    }

    /** As above, stamping the rep who took the order (O7 D2). */
    @Transactional
    public OrderDTO book(OrderDTO dto, Long orgId, Long userId, String bookedByName) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty())
            throw new ValidationException("An order needs at least one line");
        if (dto.getCustomerName() == null || dto.getCustomerName().isBlank())
            throw new ValidationException("Which outlet is this order for?");

        // Idempotent on the caller's key, exactly as placePublic is: a booker on a poor connection will retry,
        // and a second order for one visit is worse than a failed one. OMS-3's lesson, applied to the field.
        String key = (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank())
                ? dto.getIdempotencyKey().trim() : null;
        if (key != null) {
            Order existing = repo.findByOrgAndIdempotencyKey(orgId, key).orElse(null);
            if (existing != null) {
                LOG.info("Booking replayed for key {} — returning existing order {}", key, existing.getOrderNo());
                return toDTOWithLines(existing);
            }
        }

        // O7 D1 — does this org review booked orders, or go straight to picking?
        //
        // ON reproduces exactly the hardcoded behaviour this replaces, so an org that never opens the
        // Configuration screen sees no change. OFF is for the one-person back office, where the same human
        // books and converts and the gate therefore segregates nothing — see the catalog entry.
        //
        // Read PER ORG rather than from the security context: a booking is always made in a known tenant, and
        // O3 learned the hard way that resolving the tenant ambiently returns the platform default to every
        // real customer while looking correct in staff-authenticated tests.
        boolean requireApproval = settingsService.getBoolFor(
                orgId, com.myplus.marketplace.config.MarketplaceSettingsCatalog.BOOKING_REQUIRE_APPROVAL);
        FulfilmentStatus entryState = requireApproval ? FulfilmentStatus.PENDING_APPROVAL : FulfilmentStatus.NEW;

        long orderSeq = repo.maxOrderSeqForOrg(orgId) + 1;
        Order o = Order.builder()
                .organizationId(orgId).userId(userId)
                .orderSeq(orderSeq)
                .orderNo(com.myplus.commerce.domain.InvoiceNumbers.order(orderSeq))
                .idempotencyKey(key)
                .customerName(dto.getCustomerName())
                .customerContact(dto.getCustomerContact())
                // O7 D2c: WHICH outlet. Without this the invoice at dispatch resolves the buyer by name and
                // creates a duplicate customer — see Order.customerId.
                .customerId(dto.getCustomerId())
                .shippingAddress(dto.getShippingAddress())
                // O7 D2: who took it. The name is stamped, not resolved later — see Order.bookedByName.
                .bookedByUserId(userId)
                .bookedByName(bookedByName)
                .total(lineTotal(dto.getItems()))     // indicative only — the invoice is priced at dispatch
                .items(toItems(dto.getItems()))
                .source("FIELD")                      // POS | STOREFRONT | FIELD — how the order was taken
                .paymentMode(dto.getPaymentMode() != null ? dto.getPaymentMode() : "CREDIT")
                .booksStatus(AWAITING_DISPATCH)
                .fulfilmentStatus(entryState)
                .build();

        Order saved;
        try {
            saved = repo.saveAndFlush(o);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            Order winner = (key == null) ? null : repo.findByOrgAndIdempotencyKey(orgId, key).orElse(null);
            if (winner == null) throw duplicate;
            LOG.info("Booking for key {} lost the insert race — returning order {}", key, winner.getOrderNo());
            return toDTOWithLines(winner);
        }
        notificationService.notify(saved, entryState.name(),
                requireApproval ? "Order booked, awaiting review" : "Order booked, ready to pick");
        return toDTOWithLines(saved);
    }

    /**
     * The warehouse admin (or the booker) amends a booked order.
     *
     * <h3>Only while it is still under review</h3>
     * {@code PENDING_APPROVAL} or {@code REJECTED} — nothing else. Once confirmed, the order is a picking
     * instruction and, once dispatched, an invoice; editing either behind the operation's back is how a
     * warehouse comes to pack something the paperwork does not describe. A confirmed order that needs changing
     * is rejected back or cancelled, which leaves a trail.
     *
     * <h3>Every amendment is recorded</h3>
     * D-2 lets two different people edit one order and D-3 lets one of them change prices; together those make
     * the trail mandatory (see {@link com.myplus.marketplace.entity.OrderAmendment}). A caller that changes
     * nothing writes no row — an empty amendment is noise in the one record that must stay readable.
     *
     * <p><b>Concurrent edits.</b> {@code Order} carries {@code @Version} (O2), so two people saving the same
     * order collide rather than silently overwriting each other. That surfaces as a 409 with a readable message
     * since the 2026-08-10 review — which is exactly the case D-2 created and the reason the fix mattered.
     */
    @Transactional
    public OrderDTO amend(Long id, OrderDTO dto, Long orgId, Long userId, String userName) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        FulfilmentStatus current = o.getFulfilmentStatus();
        if (current != FulfilmentStatus.PENDING_APPROVAL && current != FulfilmentStatus.REJECTED)
            throw new ValidationException("Only an order still under review can be amended. This one is "
                    + current + " — reject it back to the booker, or cancel it.");

        List<java.util.Map<String, String>> changes = new ArrayList<>();
        applyHeaderAmendments(o, dto, changes);
        applyLineAmendments(o, dto, changes);

        if (changes.isEmpty()) return toDTOWithLines(o);   // nothing changed — do not write an empty audit row

        o.setTotal(lineTotalOfItems(o.getItems()));
        Order saved = repo.save(o);

        // O7 D1b — tell the reviewer what this amendment costs, NOW rather than when the van is loading.
        List<String> policyWarnings = policyWarningsFor(saved, orgId);

        amendmentRepository.save(com.myplus.marketplace.entity.OrderAmendment.builder()
                .orderId(saved.getId()).organizationId(orgId)
                .userId(userId).userName(userName)
                .summary(summarise(changes))
                .changes(toJson(changes))
                .reason(dto.getAmendmentReason())
                .createdAt(java.time.LocalDateTime.now())
                .build());
        notificationService.notify(saved, saved.getFulfilmentStatus().name(), "Amended: " + summarise(changes));

        OrderDTO out = toDTOWithLines(saved);
        out.setPolicyWarnings(policyWarnings);
        return out;
    }

    /**
     * O7 D1b — what the sale path WOULD say about this order, asked before anyone dispatches it.
     *
     * <h3>The gap this closes</h3>
     * Margin and credit are enforced at DISPATCH, by the sale path, exactly as for every other sale. So a
     * reviewer amending an order learned that their amendment loses money, or puts the outlet over its limit,
     * when the van was already loading. §6 D-3 asked for the re-check at amend time; D1 shipped without it and
     * §8.1 recorded the departure. This is it.
     *
     * <h3>Built from the SAME request dispatch sends</h3>
     * Same customer id (D2c), same per-line price and discount, same channel. If this request were assembled
     * differently the two answers could legitimately differ, and a reviewer told "fine" would meet a refusal
     * at dispatch with nothing to explain it. The one deliberate difference is quantity: the check asks about
     * the WHOLE order, because at amend time nobody has decided what goes on the first parcel.
     *
     * <h3>Advisory, and it must never block the amendment</h3>
     * The amendment is already saved by the time this runs. Refusing to save because a sale WOULD fail later
     * takes the decision away from the person the review step exists to serve — D1 established that both
     * booker and admin may revise. Tell them; let them choose.
     *
     * <h3>Never fatal</h3>
     * business-service being slow or down must not fail an amendment that has already been written. A missing
     * forecast is a missing convenience; a failed amend is lost work. Returns empty and says so in the log.
     */
    private List<String> policyWarningsFor(Order order, Long orgId) {
        try {
            List<com.myplus.commerce.contracts.dto.SaleRecordRequest.Line> lines = new ArrayList<>();
            for (OrderItem item : order.getItems()) {
                if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) continue;
                lines.add(com.myplus.commerce.contracts.dto.SaleRecordRequest.Line.builder()
                        .productId(item.getProductId())
                        .quantity(item.getQuantity().floatValue())
                        .unitPrice(item.getPrice())
                        .discount(item.getDiscount())
                        .build());
            }
            if (lines.isEmpty()) return List.of();

            com.myplus.commerce.contracts.dto.PolicyCheckResponse r = asStore(orgId, () ->
                    tradeClient.checkPolicy(com.myplus.commerce.contracts.dto.SaleRecordRequest.builder()
                            .organizationId(orgId)
                            .channel("FIELD")
                            .customer(com.myplus.commerce.contracts.dto.SaleRecordRequest.Customer.builder()
                                    .customerId(order.getCustomerId())
                                    .name(order.getCustomerName())
                                    .contact(order.getCustomerContact())
                                    .build())
                            .lines(lines)
                            .build()));

            return (r == null || r.getWarnings() == null) ? List.of() : r.getWarnings();
        } catch (Exception e) {
            LOG.warn("D1b: policy pre-check unavailable for order {} — amendment stands, forecast omitted: {}",
                    order.getOrderNo(), e.toString());
            return List.of();
        }
    }

    /**
     * The admin confirms a booked order: it becomes a picking instruction.
     *
     * <p>Still no invoice — that is raised at dispatch, from what actually goes out (D-1). What changes is that
     * the order is now the warehouse's work rather than the booker's proposal.
     *
     * <h3>Stock is NOT held here, and that is a known gap</h3>
     * The approved design said "reserve at confirm, invoice at dispatch". Reserving would mean marketplace
     * holding inventory again — the reservation saga <b>O1 deliberately deleted</b>, because it produced holds
     * with no invoice behind them. Re-adding it to satisfy a design line would undo a correctness fix, so this
     * confirms without reserving and the stock is taken atomically at dispatch by the existing sale path.
     *
     * <p><b>The consequence, stated plainly:</b> two orders confirmed for the last carton will both be
     * confirmed, and the second will fail or backorder at dispatch. Doing it properly needs a reserve operation
     * on the trade contract so business-service — which owns stock — performs the hold. That is <b>D1b</b>, and
     * it is a contract change, not a marketplace one.
     */
    @Transactional
    public OrderDTO confirm(Long id, Long orgId, Long userId) {
        Order o = requirePending(id, orgId, userId, "confirmed");
        o.setFulfilmentStatus(FulfilmentStatus.NEW);
        o.setRejectionReason(null);          // a confirmed order carries no standing refusal
        Order saved = repo.save(o);

        // O7 D1c — the promise is now backed by stock. Advisory: a refusal is reported, not enforced, because
        // the admin is the person entitled to decide whether to promise goods the shop has not got.
        String couldNotHold = orderStockHoldService.hold(saved, orgId);

        /*
         * OMS O8 — a shop with no warehouse step dispatches at approval.
         *
         * ⚠ THE PACK STEP IS PERFORMED, NOT SKIPPED, and the distinction is the whole design. A field order's
         * invoice is raised BY the shipment (DispatchInvoiceService, called from ShipmentService). Remove the
         * step and the order is never invoiced — OMS-1, the defect this programme began with. So this records
         * the parcel through the ordinary path, leaving ShipmentService the only writer of shipments and the
         * only trigger of a dispatch invoice.
         *
         * Everything downstream is untouched because none of it can tell who recorded the parcel: the round
         * sheet already selects SHIPPED and PARTIALLY_SHIPPED, delivery keying already works from SHIPPED, and
         * a short delivery still raises its credit note where the shortfall is actually discovered.
         *
         * ⚠ REFUSED WHEN THE STOCK COULD NOT BE HELD. Ordinarily a failed hold is advisory — the admin is
         * entitled to promise goods the shop has not got, and the warehouse finds out at picking. There IS no
         * picking here, so the same advisory would dispatch and invoice stock that does not exist. The order is
         * left at NEW to be packed by hand: the operator loses the shortcut, not the order.
         */
        List<String> warnings = new ArrayList<>();
        if (couldNotHold != null) {
            warnings.add("Confirmed, but the stock could not be set aside: " + couldNotHold);
        }

        if (autoDispatchEnabled(orgId)) {
            /*
             * ⚠ SCAN-TO-PACK AND AUTO-DISPATCH CANNOT BOTH BE TRUE.
             *
             * Nobody scans anything on this path, so a tenant with both switched on has asked for a
             * verification that cannot happen. O5d withdrew a setting for being exactly this — enforced
             * correctly, satisfiable by nothing — so the combination is reported rather than resolved
             * silently in either direction. Choosing for them would be choosing which of their two stated
             * intentions to ignore.
             */
            if (settingsService.getBoolFor(orgId,
                    com.myplus.marketplace.config.MarketplaceSettingsCatalog.PACK_SCAN_REQUIRED)) {
                warnings.add("Not dispatched automatically: this shop also requires items to be scanned when "
                        + "packing. Turn one of the two settings off.");
            } else if (couldNotHold != null) {
                warnings.add("Not dispatched automatically — pick and pack it by hand once the stock is in.");
            } else {
                String failed = autoDispatch(saved, orgId, userId);
                if (failed != null) warnings.add("Not dispatched automatically: " + failed);
                saved = repo.findById(saved.getId()).orElse(saved);   // re-read: the shipment moved the status
            }
        }

        notificationService.notify(saved, saved.getFulfilmentStatus().name(),
                saved.getFulfilmentStatus() == FulfilmentStatus.NEW
                        ? "Order confirmed — ready to pack"
                        : "Order confirmed and dispatched");
        OrderDTO out = toDTOWithLines(saved);
        if (!warnings.isEmpty()) out.setPolicyWarnings(warnings);
        return out;
    }

    /** Does this tenant collapse pack and dispatch into the approval? Off unless asked for. */
    private boolean autoDispatchEnabled(Long orgId) {
        return settingsService.getBoolFor(orgId,
                com.myplus.marketplace.config.MarketplaceSettingsCatalog.AUTO_DISPATCH_ON_APPROVAL);
    }

    /**
     * Record the whole order as dispatched, through the ordinary shipment path.
     *
     * <p>Every line at its FULL outstanding quantity — this is the flow for a shop that loads the van with the
     * order as booked. Anything the outlet then refuses is recorded at delivery keying, which raises the credit
     * note. That is where a short delivery is actually discovered, and recording it here instead would be the
     * system inventing a fact nobody has observed yet.
     *
     * <p>Lines are NOT marked {@code verified}: nobody scanned anything. Claiming a verification that did not
     * happen is the trap O5d's withdrawn setting was withdrawn for.
     *
     * @return null when the parcel was recorded, or a reason to report as a warning — the approval itself
     *         stands either way, because refusing an approval over a failed dispatch would lose the review
     *         decision the admin has just made
     */
    private String autoDispatch(Order order, Long orgId, Long userId) {
        try {
            List<ShipmentDTO.LineRequest> lines = new ArrayList<>();
            for (OrderItem item : order.getItems()) {
                int shipped = item.getQuantityShipped() == null ? 0 : item.getQuantityShipped();
                int outstanding = (item.getQuantity() == null ? 0 : item.getQuantity()) - shipped;
                if (outstanding <= 0) continue;
                ShipmentDTO.LineRequest line = new ShipmentDTO.LineRequest();
                line.setOrderItemId(item.getId());
                line.setQuantity(outstanding);
                lines.add(line);
            }
            if (lines.isEmpty()) return "there was nothing outstanding to dispatch.";

            ShipmentDTO.Request req = new ShipmentDTO.Request();
            req.setLines(lines);
            req.setNote("Dispatched on approval (no warehouse step)");
            shipmentService.ship(order.getId(), req, orgId, userId);
            return null;
        } catch (RuntimeException e) {
            // The approval has already been saved and is not undone by this. A shop that cannot dispatch
            // automatically still has an approved order it can pack by hand.
            LOG.warn("Auto-dispatch on approval failed for order {}", order.getOrderNo(), e);
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * The admin rejects a booked order, with a reason.
     *
     * <p>The reason is <b>required</b>: a rejection without one leaves the booker unable to fix the order or
     * explain it to the shop, so the visit is wasted twice. Not terminal — D-2 settled that the order can be
     * revised and resubmitted, which is why {@code REJECTED → PENDING_APPROVAL} is a legal move and why the
     * reason survives the revision.
     */
    @Transactional
    public OrderDTO reject(Long id, String reason, Long orgId, Long userId) {
        if (reason == null || reason.isBlank())
            throw new ValidationException("Say why it is rejected — the booker cannot fix it otherwise.");
        Order o = requirePending(id, orgId, userId, "rejected");
        o.setFulfilmentStatus(FulfilmentStatus.REJECTED);
        o.setRejectionReason(reason.trim());
        Order saved = repo.save(o);
        /*
         * O7 D1c — deliberately NO stock release here, and the reason is worth stating.
         *
         * `requirePending` allows a rejection only from PENDING_APPROVAL, and stock is held at CONFIRM. So an
         * order reachable by this method has never had a hold, and there is nothing to give back. A release
         * call here would be a remote round trip that always finds nothing.
         *
         * The gate proved this rather than the reading: a case written as "reject a confirmed order and the
         * stock comes back" was answered "Only an order awaiting review can be rejected. This one is NEW."
         * Cancel is the way a confirmed order is undone, and that is where the release lives.
         */
        notificationService.notify(saved, FulfilmentStatus.REJECTED.name(), "Rejected: " + reason.trim());
        return toDTOWithLines(saved);
    }

    /** Resubmit a rejected order for review (D-2 — either the booker or the admin may). */
    @Transactional
    public OrderDTO resubmit(Long id, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (o.getFulfilmentStatus() != FulfilmentStatus.REJECTED)
            throw new ValidationException("Only a rejected order can be resubmitted.");
        o.setFulfilmentStatus(FulfilmentStatus.PENDING_APPROVAL);
        Order saved = repo.save(o);
        notificationService.notify(saved, FulfilmentStatus.PENDING_APPROVAL.name(), "Revised and resubmitted");
        return toDTOWithLines(saved);
    }

    /** The amendment trail for one order, oldest first. */
    @Transactional(readOnly = true)
    public List<com.myplus.marketplace.dto.OrderAmendmentDTO> amendments(Long id, Long orgId, Long userId) {
        repo.findByIdScoped(id, orgId, userId)      // anti-IDOR: prove the order is ours before reading its trail
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        List<com.myplus.marketplace.dto.OrderAmendmentDTO> out = new ArrayList<>();
        for (com.myplus.marketplace.entity.OrderAmendment a
                : amendmentRepository.findByOrderIdOrderByCreatedAtAsc(id)) {
            com.myplus.marketplace.dto.OrderAmendmentDTO d = new com.myplus.marketplace.dto.OrderAmendmentDTO();
            d.setUserName(a.getUserName());
            d.setSummary(a.getSummary());
            d.setChanges(a.getChanges());
            d.setReason(a.getReason());
            d.setAt(a.getCreatedAt());
            out.add(d);
        }
        return out;
    }

    private Order requirePending(Long id, Long orgId, Long userId, String verb) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (o.getFulfilmentStatus() != FulfilmentStatus.PENDING_APPROVAL)
            throw new ValidationException("Only an order awaiting review can be " + verb
                    + ". This one is " + o.getFulfilmentStatus() + ".");
        return o;
    }

    /** Header fields the reviewer may correct. Each recorded only when it actually changes. */
    private void applyHeaderAmendments(Order o, OrderDTO dto, List<java.util.Map<String, String>> changes) {
        if (dto.getCustomerName() != null && !dto.getCustomerName().equals(o.getCustomerName())) {
            changes.add(change("customerName", o.getCustomerName(), dto.getCustomerName()));
            o.setCustomerName(dto.getCustomerName());
        }
        if (dto.getCustomerContact() != null && !dto.getCustomerContact().equals(o.getCustomerContact())) {
            changes.add(change("customerContact", o.getCustomerContact(), dto.getCustomerContact()));
            o.setCustomerContact(dto.getCustomerContact());
        }
        if (dto.getShippingAddress() != null && !dto.getShippingAddress().equals(o.getShippingAddress())) {
            changes.add(change("shippingAddress", o.getShippingAddress(), dto.getShippingAddress()));
            o.setShippingAddress(dto.getShippingAddress());
        }
        if (dto.getDiscountAmount() != null && cmp(dto.getDiscountAmount(), o.getDiscountAmount()) != 0) {
            changes.add(change("discountAmount", str(o.getDiscountAmount()), str(dto.getDiscountAmount())));
            o.setDiscountAmount(dto.getDiscountAmount());
        }
        // The delivery date the outlet was promised. Amending it is a promise to the customer, so it is
        // recorded like any other change rather than moved silently.
        if (dto.getPromisedDate() != null && !dto.getPromisedDate().equals(o.getPromisedDate())) {
            changes.add(change("promisedDate", str(o.getPromisedDate()), str(dto.getPromisedDate())));
            o.setPromisedDate(dto.getPromisedDate());
        }
    }

    /**
     * Line-level amendments: quantity, price (D-3), and removal.
     *
     * <p>Matched on the line's own id. Matching on {@code productId} would look simpler and break on the real
     * case — the same product on two lines at two prices, which is routine in trade orders.
     *
     * <p>A line whose quantity is amended to zero is REMOVED, because an order line for nothing is not a line;
     * a picker would still see it and wonder what it means.
     */
    private void applyLineAmendments(Order o, OrderDTO dto, List<java.util.Map<String, String>> changes) {
        if (dto.getItems() == null) return;
        java.util.Map<Long, OrderDTO.Line> byId = new java.util.HashMap<>();
        for (OrderDTO.Line l : dto.getItems()) if (l.getId() != null) byId.put(l.getId(), l);

        java.util.Iterator<OrderItem> it = o.getItems().iterator();
        while (it.hasNext()) {
            OrderItem item = it.next();
            OrderDTO.Line in = byId.get(item.getId());
            if (in == null) continue;                       // not mentioned by the caller — left alone

            if (in.getQuantity() != null && in.getQuantity() <= 0) {
                changes.add(change("line[" + name(item) + "]", nzInt(item.getQuantity()) + " x", "removed"));
                it.remove();
                continue;
            }
            if (in.getQuantity() != null && in.getQuantity() != nzInt(item.getQuantity())) {
                changes.add(change("line[" + name(item) + "].quantity",
                        String.valueOf(nzInt(item.getQuantity())), String.valueOf(in.getQuantity())));
                item.setQuantity(in.getQuantity());
            }
            // D-3: the admin may change price. The MARGIN policy that governs it is enforced by the sale path
            // at dispatch (`pos.sale.marginPolicy`, whole-document, after discounts) — see D1b in the slice
            // doc for why it is not also checked here yet.
            if (in.getPrice() != null && cmp(in.getPrice(), item.getPrice()) != 0) {
                changes.add(change("line[" + name(item) + "].price", str(item.getPrice()), str(in.getPrice())));
                item.setPrice(in.getPrice());
            }
            // The concession is amendable for the same reason the price is — the review desk is where a rep's
            // over-generous discount gets pulled back — and it is recorded in the amendment trail like any
            // other change, so "who agreed to this" survives.
            if (in.getDiscount() != null && cmp(in.getDiscount(), item.getDiscount()) != 0) {
                changes.add(change("line[" + name(item) + "].discount", str(item.getDiscount()), str(in.getDiscount())));
                item.setDiscount(in.getDiscount());
            }
        }
        if (o.getItems().isEmpty())
            throw new ValidationException("An order cannot have every line removed — reject or cancel it instead.");
    }

    private static String name(OrderItem i) {
        return i.getProductName() != null ? i.getProductName() : ("product " + i.getProductId());
    }

    private static java.util.Map<String, String> change(String field, String from, String to) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("field", field);
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    private static int cmp(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b);
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static String summarise(List<java.util.Map<String, String>> changes) {
        if (changes.size() == 1) return changes.get(0).get("field") + " changed";
        return changes.size() + " changes: " + changes.stream().map(c -> c.get("field")).limit(4)
                .collect(Collectors.joining(", ")) + (changes.size() > 4 ? "…" : "");
    }

    /** Hand-built rather than Jackson: the shape is three known string fields and this cannot throw. */
    private static String toJson(List<java.util.Map<String, String>> changes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < changes.size(); i++) {
            java.util.Map<String, String> c = changes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"field\":\"").append(esc(c.get("field")))
              .append("\",\"from\":\"").append(esc(c.get("from")))
              .append("\",\"to\":\"").append(esc(c.get("to"))).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /**
     * The order's indicative value: sum of (qty x price) LESS each line's discount.
     *
     * <p>Netting the discount here is what makes the figure the rep quotes at the counter the same one the
     * office sees on the review screen. It stays indicative either way — the invoice is priced at dispatch
     * from what physically went out — but a quoted total that ignored a concession the rep had just agreed
     * would be wrong in the one direction that starts an argument.
     *
     * <p>Clamped at zero per line: a discount larger than the line is a typo, not a credit.
     */
    private static BigDecimal lineTotal(List<OrderDTO.Line> lines) {
        BigDecimal t = BigDecimal.ZERO;
        if (lines == null) return t;
        for (OrderDTO.Line l : lines) {
            if (l.getPrice() == null || l.getQuantity() == null) continue;
            BigDecimal gross = l.getPrice().multiply(BigDecimal.valueOf(l.getQuantity()));
            t = t.add(netOfDiscount(gross, l.getDiscount()));
        }
        return t;
    }

    private static BigDecimal lineTotalOfItems(List<OrderItem> items) {
        BigDecimal t = BigDecimal.ZERO;
        if (items == null) return t;
        for (OrderItem i : items) {
            if (i.getPrice() == null) continue;
            BigDecimal gross = i.getPrice().multiply(BigDecimal.valueOf(nzInt(i.getQuantity())));
            t = t.add(netOfDiscount(gross, i.getDiscount()));
        }
        return t;
    }

    /** One line, less its concession, never below zero. */
    private static BigDecimal netOfDiscount(BigDecimal gross, BigDecimal discount) {
        if (discount == null || discount.signum() <= 0) return gross;
        BigDecimal net = gross.subtract(discount);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }

    @Transactional
    public OrderDTO record(OrderDTO dto, Long orgId, Long userId) {
        // OMS O5e step 1 (OMS-5) — ONE INVOICE IS ONE ORDER.
        //
        // Shipped first and alone, deliberately: it is safe by itself (a repeat post becomes a no-op) and it is
        // what makes the rest of O5e safe. During the migration BOTH the browser and business-service will
        // record the order; without this key that is two writers and two orders for one sale — the defect O2's
        // OMS-3 work removed from the storefront, reappearing on the POS path.
        //
        // invoiceNo is the natural key: it is already unique per org and already identifies the sale.
        String key = dto.getInvoiceNo();
        if (key != null && !key.isBlank()) {
            Order existing = repo.findByOrgAndIdempotencyKey(orgId, key).orElse(null);
            if (existing != null) {
                LOG.info("POS order for invoice {} replayed — returning existing order {}", key, existing.getId());
                return toDTO(existing);
            }
        }

        // OMS O5e step 2 — a POS order becomes a first-class order.
        //
        // Both additions are ADDITIVE and backward-compatible: the browser still posts
        // {invoiceNo, customerName, total} and keeps working. `items` is used when the caller supplies it,
        // which business-service will in step 3; until then a POS order simply has none, exactly as before.
        //
        // The SO- number is allocated unconditionally, because there is no reason a POS order should be the one
        // kind of order a merchant cannot quote or track. Same MAX+1 allocation placePublic uses, made race-safe
        // by UNIQUE(organization_id, order_seq).
        long orderSeq = repo.maxOrderSeqForOrg(orgId) + 1;

        Order o = Order.builder()
                .organizationId(orgId).userId(userId)
                .orderSeq(orderSeq)
                .orderNo(com.myplus.commerce.domain.InvoiceNumbers.order(orderSeq))
                .invoiceNo(dto.getInvoiceNo())
                .idempotencyKey(key)
                .customerName(dto.getCustomerName())
                // OMS O5e step 3: this is now the total the SALE posted to the ledger. The monolith reads the
                // invoice back from business-service (`/getReceipt`) and sends its `grandTotal`, so the browser's
                // cart arithmetic — gap B — is out of the path. marketplace cannot verify that from here; under
                // §2.5's option C the orchestrator is what guarantees it, which is the trade that option names.
                .total(dto.getTotal())
                // Lines are what make cancel/return able to restore stock at all — the guard is
                // `!items.isEmpty()`. Step 3 supplies them from the invoice's persisted rows, which is what
                // finally closes OMS-5: a POS order can now be cancelled with its goods put back.
                .items(toItems(dto.getItems()))
                // O1's books marker. An order that names an invoice IS in the books — leaving it null made the
                // one order source that definitely has revenue behind it the only one that would not say so, and
                // made the `REVERSED` stamp a cancel writes a transition out of nothing.
                .booksStatus(hasText(dto.getInvoiceNo()) ? "POSTED" : null)
                .shippingAddress(dto.getShippingAddress())
                .source("POS").paymentMode(dto.getPaymentMode() != null ? dto.getPaymentMode() : "POS")
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        Order saved;
        try {
            // Flush now so a concurrent double-post surfaces HERE as a duplicate key rather than at commit.
            // UNIQUE(organization_id, idempotency_key) is what actually makes this race-safe — the read above
            // only handles the common sequential replay.
            saved = repo.saveAndFlush(o);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            Order winner = (key == null) ? null : repo.findByOrgAndIdempotencyKey(orgId, key).orElse(null);
            if (winner == null) throw duplicate;   // a different constraint — do not swallow it
            LOG.info("POS order for invoice {} lost the insert race — returning order {}", key, winner.getId());
            return toDTO(winner);
        }
        notificationService.notify(saved, "NEW", "Order received");   // slice 57: start the timeline
        return toDTO(saved);
    }

    /** Public guest order from the storefront (slice 47) — org comes from the request (no JWT identity). COD. */
    @Transactional
    public OrderDTO placePublic(OrderDTO dto) {
        if (dto.getOrganizationId() == null)
            throw new ValidationException("Store (organizationId) is required");
        if (dto.getCustomerName() == null || dto.getCustomerName().isBlank())
            throw new ValidationException("Your name is required");

        Long org = dto.getOrganizationId();

        // slice 61: link the order to the shopper's account when a valid session token is supplied.
        Long customerAccountId = null;
        if (dto.getCustomerToken() != null && !dto.getCustomerToken().isBlank()) {
            customerAccountId = customerRepo.findBySessionToken(dto.getCustomerToken().trim())
                    .filter(c -> org.equals(c.getOrganizationId()))   // only link within the same store
                    .map(com.myplus.marketplace.entity.StorefrontCustomer::getId).orElse(null);
        }

        // OMS O1: the order goes through business-service's sale path — the SAME one the till uses — which
        // reserves (FEFO), writes the invoice, confirms, applies tax, snapshots COGS, records the payment,
        // emits the GL event and audits, all idempotently. Marketplace no longer runs a reservation saga of its
        // own: before O1 it reserved and confirmed here and produced NO invoice, so every online sale was
        // missing from the P&L, trial balance, tax register and AR.
        boolean card = "CARD".equalsIgnoreCase(dto.getPaymentMode());
        // The cart token is a stable per-checkout handle, so it doubles as the idempotency key: a double-submit
        // of the same cart replays the SAME invoice instead of minting a second one. Falls back to a fresh key
        // for a cart-less (direct API) order, which then has nothing to deduplicate against.
        String idempotencyKey = (dto.getCartToken() != null && !dto.getCartToken().isBlank())
                ? "SF-" + dto.getCartToken() : UUID.randomUUID().toString();

        // OMS-3: return the EXISTING order for a repeated checkout. O1 already made the sale idempotent on this
        // same key, so without this a double-submit replayed one invoice but inserted a second order — picked
        // and shipped twice. Checked first for the common case; UNIQUE(org, key) is what actually makes it
        // race-safe when two submits arrive together (see the catch below).
        Order existing = repo.findByOrgAndIdempotencyKey(org, idempotencyKey).orElse(null);
        if (existing != null) {
            LOG.info("Storefront checkout replayed for key {} — returning existing order {}",
                    idempotencyKey, existing.getOrderNo());
            return toDTO(existing);
        }

        // OMS O5c — decide what can be filled BEFORE asking for the sale.
        //
        // The alternative was teaching the sale path to accept a PARTIAL reservation, which would have meant
        // editing SagaSellService — the single revenue path. Splitting here instead means the sale we request
        // is fully satisfiable, so `reserve` succeeds by its existing all-or-nothing rule and that code is not
        // touched. The read is advisory: if stock is taken between it and the sale, the reserve still refuses
        // and the shopper gets today's out-of-stock message, which is the safe direction.
        BackorderSplit.Result split = splitForBackorder(dto, org);
        if (split != null) {
            applyFillNow(dto, split);
            // Promise a date at the moment the shortfall is accepted. Set only when something is actually owed:
            // an order filled in full has nothing to promise, and stamping one anyway would make every order
            // ageable and the "late" view meaningless.
            dto.setPromisedDate(backorderPolicy.promisedDate(org));
        }

        // O5c — a TOTAL shortfall has nothing to invoice.
        //
        // You invoice what you DELIVER. When none of the order can be filled today, no goods move and no sale
        // exists yet, so raising one would recognise revenue and tax for something that has not happened. The
        // order is created with no invoice and booksStatus BACKORDER_PENDING, and is invoiced when it ships.
        //
        // This deliberately recreates the SHAPE O1 removed (an order with no invoice) — but not the defect.
        // O1's orders had already taken stock and charged a card while producing no books; this one has taken
        // neither, and booksStatus is exactly the field O1 added so an unbooked order stays findable instead of
        // silently missing from the ledger.
        boolean nothingToInvoice = split != null && split.totalFillNow() == 0;
        if (nothingToInvoice && !backorderPolicy.acceptFullShortfall(org)) {
            // The shop has chosen to accept only orders it can PARTLY fill.
            throw new ValidationException("Sorry, that item is out of stock at the moment.");
        }

        SaleRecordResult sale = null;
        try {
            if (!nothingToInvoice)
                sale = asStore(org, () -> tradeClient.recordSale(toSaleRequest(dto, org, idempotencyKey)));
        } catch (RuntimeException saleFailure) {
            // Out of stock (or any refusal) → nothing reserved, nothing invoiced and, critically, NOTHING
            // CHARGED: the card is only charged after the sale exists. This ordering is why an unavailable item
            // can never leave a shopper out of pocket.
            //
            // RELAY the real reason. O1 replaced the old "out of stock: <why>" with a generic sentence, which
            // lost the one thing the shopper needs — a "no longer available" that never says what or why reads
            // as a glitch, and support cannot act on it either. The pre-O1 behaviour was better and is restored.
            LOG.warn("Storefront order for org {} could not be recorded as a sale", org, saleFailure);
            throw new ValidationException(checkoutFailureMessage(saleFailure));
        }
        if (!nothingToInvoice && (sale == null || sale.getInvoiceNo() == null))
            throw new ValidationException("The order could not be completed. Please try again.");

        // Charge the SERVER's total, never the client's (OMS-5). dto.getTotal() is display-only from here on.
        String payStatus = "PENDING", payRef = null;
        BigDecimal charged = sale != null && sale.getGrandTotal() != null ? sale.getGrandTotal() : dto.getTotal();
        // O5c: nothing invoiced means nothing to charge YET. Taking a card payment for goods that have not been
        // sold would be money held against no invoice — the shopper is charged when the order is dispatched.
        if (card && !nothingToInvoice) {
            PaymentGateway.Charge ch = paymentGateway.charge(dto.getCardToken(), charged);
            if (!ch.success()) {
                // The sale exists, so a decline must REVERSE it — a void restores stock, refunds nothing (nothing
                // was paid) and nets Sales + AR back to zero. Leaving it would book revenue for an order the
                // shopper never paid for.
                reverseQuietly(org, sale.getInvoiceNo(), "Payment declined");
                throw new ValidationException("Payment declined: " + ch.declineReason());
            }
            payStatus = "PAID";
            payRef = ch.chargeId();
        }

        // OMS-8: the merchant-facing number, allocated MAX+1 per org inside this transaction and guarded by
        // UNIQUE(organization_id, order_seq) — the allocation invoice_seq/credit_note_seq/quote_seq all use.
        long orderSeq = repo.maxOrderSeqForOrg(org) + 1;

        Order o = Order.builder()
                .organizationId(org)
                .orderSeq(orderSeq)
                .orderNo(com.myplus.commerce.domain.InvoiceNumbers.order(orderSeq))
                .idempotencyKey(idempotencyKey)      // OMS-3: same key the sale deduplicates on
                .invoiceNo(sale != null ? sale.getInvoiceNo() : null)   // O1: the trade sale this order IS
                // O5c: BACKORDER_PENDING is a THIRD books state, distinct from LEGACY_UNPOSTED. Both mean "no
                // invoice", but this one is correct and expected — nothing has been delivered — whereas
                // LEGACY_UNPOSTED marks a pre-O1 order that took stock and money without ever reaching the
                // books. Collapsing them would bury a real backlog inside a normal one.
                .booksStatus(sale != null ? "POSTED" : "BACKORDER_PENDING")
                .customerName(dto.getCustomerName())
                .customerContact(dto.getCustomerContact())
                .total(charged)                      // the server's figure, not the client's
                .subTotal(dto.getSubTotal()).taxTotal(dto.getTaxTotal())
                .shippingFee(dto.getShippingFee()).shippingMethod(dto.getShippingMethod())
                .couponCode(dto.getCouponCode()).discountAmount(dto.getDiscountAmount())
                .shippingAddress(dto.getShippingAddress())
                .promisedDate(dto.getPromisedDate())            // O5c: set only when something is owed
                .source("STOREFRONT").paymentMode(card ? "CARD" : "COD")
                .paymentStatus(payStatus).paymentRef(payRef)
                .customerAccountId(customerAccountId)
                .items(toItems(dto.getItems()))
                // O5c: NEW is only right when the order can be filled. With a shortfall it is BACKORDERED from
                // the moment it is accepted — the status is DERIVED from the line quantities (O5b), and
                // hardcoding NEW here made a backordered order claim it was ready to pack.
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        ShipmentService.applyProjection(o);
        Order saved;
        try {
            saved = repo.saveAndFlush(o);   // flush now so a duplicate-key race surfaces HERE, not at commit
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // OMS-3, the race the pre-check cannot cover: two submits arrived together, both missed the read,
            // and UNIQUE(organization_id, idempotency_key) let exactly one through. The loser returns the
            // WINNER's order rather than failing the shopper — and must NOT reverse the sale, because that sale
            // belongs to the order that won.
            Order winner = repo.findByOrgAndIdempotencyKey(org, idempotencyKey).orElse(null);
            if (winner != null) {
                LOG.info("Concurrent checkout for key {} — returning the order that won ({})",
                        idempotencyKey, winner.getOrderNo());
                return toDTO(winner);
            }
            reverseQuietly(org, sale.getInvoiceNo(), "Order could not be recorded");
            throw duplicate;
        } catch (RuntimeException writeFailure) {
            // The sale is already in the books; if we cannot record our own order row, reverse it rather than
            // leave an invoice with no order behind it.
            reverseQuietly(org, sale.getInvoiceNo(), "Order could not be recorded");
            throw writeFailure;
        }

        notificationService.notify(saved, "NEW", "Order placed");   // slice 57: start the timeline
        cartService.markConverted(org, dto.getCartToken());          // slice 68: empty the persistent cart
        return toDTO(saved);
    }

    /**
     * Turn a failed {@code recordSale} into something a shopper can act on.
     *
     * <p>business-service refuses with a specific reason (most often insufficient stock, naming the product).
     * That reason travels in the 4xx body, so it is extracted and relayed rather than flattened — the same rule
     * the storefront already applies to a declined payment. Only when nothing readable comes back do we fall
     * back to generic wording, and even that names STOCK, because it is overwhelmingly the cause and "no longer
     * available" alone tells the shopper nothing.
     */
    private String checkoutFailureMessage(RuntimeException failure) {
        // Extraction lives in DownstreamMessage so the dispatch path relays refusals the same way this one
        // always has. The WORDING stays here: a shopper and a packer need different sentences around the same
        // downstream reason, and only the caller knows which it is talking to.
        String reason = com.myplus.marketplace.support.DownstreamMessage.of(failure);
        return reason != null ? "Sorry — " + reason
                : "Sorry, an item in your cart is out of stock or unavailable.";
    }

    /**
     * OMS O5c — backorders that can now be filled, and those still waiting.
     *
     * <h3>Why this is a READ and not a sweeper</h3>
     * §3.3 of the design assumed a scheduled sweeper, by analogy with O5a's. That analogy does not hold. O5a
     * needed a job because it had to <b>mutate</b> — stranded holds had to be released or the stock stayed
     * unsellable forever. Here nothing needs mutating: "can this backorder be filled now?" is entirely derived
     * from stock that already exists, so a query answers it exactly and a stored "ready" flag would only start
     * going stale the moment stock moved. Same reasoning as {@code late} being derived rather than stored.
     *
     * <p>It deliberately does not allocate. Taking goods for an old order ahead of a customer standing at the
     * till is a merchant's decision; this shows them the choice and O5b's Ship action carries it out.
     *
     * <h3>Paged since the 2026-08-10 review (R5)</h3>
     * This was the last unbounded read in the service, and the one that actually grows: a shop's backorder book
     * grows with its trade. Oldest promise first, so page 1 is the most overdue work.
     *
     * <p><b>The honest limitation, stated because it cannot be designed away here:</b> {@code readyOnly} filters
     * the PAGE, not the query. Readiness is "does inventory have the owed quantity right now", which lives in
     * another service and cannot be a SQL predicate — so a page of 25 outstanding orders may yield fewer than 25
     * ready ones. {@code totalElements} therefore counts what is OUTSTANDING, which is the number a merchant is
     * actually tracking; the filtered content is what they can act on from this page. Making
     * {@code ready=true} exact would mean walking the whole book on every request, which is precisely the
     * unbounded read being removed. An exact ready-only view needs a stock projection marketplace can query —
     * a later slice, and the same shape as INV-L.
     *
     * <p>Paging also fixed a perf defect nobody had noticed: readiness was read for the ENTIRE backlog on every
     * call. It is now read once for the page in hand.
     */
    @Transactional(readOnly = true)
    public com.myplus.common.web.PageResponse<OrderDTO> backordersOutstanding(
            Long orgId, Long userId, boolean readyOnly, int page, int size) {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), com.myplus.marketplace.dto.OrderQuery.clampSize(size));
        org.springframework.data.domain.Page<Order> outstanding =
                repo.pageOutstandingBackorders(orgId, userId, pageable);
        if (outstanding.isEmpty())
            return new com.myplus.common.web.PageResponse<>(List.of(), outstanding.getNumber(),
                    outstanding.getSize(), outstanding.getTotalElements(), outstanding.getTotalPages(), true);

        java.util.Map<Long, Float> sellable = new java.util.HashMap<>();
        try {
            java.util.Map<Long, java.util.Map<String, Float>> detail =
                    asStore(orgId, () -> backorderPolicy.readSellableAsStore(orgId));
            if (detail != null)
                detail.forEach((pid, m) -> sellable.put(pid, m == null ? 0f : m.getOrDefault("sellable", 0f)));
        } catch (RuntimeException readFailed) {
            // Show the backlog without the readiness flag rather than an error page: knowing WHAT is owed is
            // useful even when we cannot say what has arrived.
            LOG.warn("Backorder readiness unknown for org {} — listing without it", orgId, readFailed);
        }

        List<OrderDTO> out = new ArrayList<>();
        for (Order o : outstanding.getContent()) {
            boolean ready = false;
            for (OrderItem it : o.getItems()) {
                int owed = it.getQuantityBackordered() == null ? 0 : it.getQuantityBackordered();
                if (owed <= 0) continue;
                Float have = sellable.get(it.getProductId());
                if (have != null && have >= owed) { ready = true; break; }
            }
            if (readyOnly && !ready) continue;
            OrderDTO d = toDTO(o);
            d.setReadyToFulfil(ready);
            out.add(d);
        }
        return new com.myplus.common.web.PageResponse<>(out, outstanding.getNumber(), outstanding.getSize(),
                outstanding.getTotalElements(), outstanding.getTotalPages(), outstanding.isLast());
    }

    // ── OMS O5c — backorders ──────────────────────────────────────────────────────────────────────────────

    /**
     * What can be filled now, or {@code null} when backorders are off for this shop (then nothing changes and an
     * unfillable checkout is refused exactly as before).
     *
     * <p>Also returns {@code null} when there is no shortfall, so the overwhelmingly common case adds one
     * inventory read and no behaviour at all.
     *
     * <p>Fails OPEN: if the availability read fails, the order proceeds unsplit and the reserve decides. A shop
     * must not stop taking orders because a stock query timed out.
     */
    private BackorderSplit.Result splitForBackorder(OrderDTO dto, Long org) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) return null;
        java.util.Map<Long, Integer> requested = new java.util.LinkedHashMap<>();
        for (OrderDTO.Line l : dto.getItems()) {
            if (l.getProductId() == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
            requested.merge(l.getProductId(), l.getQuantity(), Integer::sum);
        }
        // Delegated so the QUOTE and this agree: a quote promising everything and a checkout backordering half
        // is the same defect as O5b's header disagreeing with its parcels.
        return backorderPolicy.splitFor(org, requested);
    }

    /** Reduce the DTO's line quantities to what will be INVOICED, remembering the shortfall for the order rows. */
    private void applyFillNow(OrderDTO dto, BackorderSplit.Result split) {
        java.util.Map<Long, Integer> fill = new java.util.HashMap<>();
        java.util.Map<Long, Integer> owed = new java.util.HashMap<>();
        for (BackorderSplit.LineSplit s : split.lines()) {
            fill.merge(s.productId(), s.fillNow(), Integer::sum);
            owed.merge(s.productId(), s.backordered(), Integer::sum);
        }
        for (OrderDTO.Line l : dto.getItems()) {
            if (l.getProductId() == null) continue;
            Integer canFill = fill.get(l.getProductId());
            Integer shortfall = owed.get(l.getProductId());
            if (canFill == null) continue;
            // Consume across duplicate lines for one product, mirroring how the split allocated them.
            int take = Math.min(l.getQuantity() == null ? 0 : l.getQuantity(), canFill);
            fill.put(l.getProductId(), canFill - take);
            l.setQuantityBackordered(shortfall == null ? 0 : shortfall);
            owed.put(l.getProductId(), 0);
            l.setQuantity(take);
        }
        // Lines that can be filled ENTIRELY not-at-all still belong on the order — they are what is owed. They
        // carry quantity 0, so toSaleRequest skips them and nothing is invoiced for them.
        LOG.info("Backorder: invoicing {} unit(s) now, {} owed", split.totalFillNow(), split.totalBackordered());
    }

    /** Build the sale request. Deliberately carries NO total — the server prices it (OMS-5). */
    private SaleRecordRequest toSaleRequest(OrderDTO dto, Long org, String idempotencyKey) {
        List<SaleRecordRequest.Line> lines = new ArrayList<>();
        if (dto.getItems() != null) {
            for (OrderDTO.Line l : dto.getItems()) {
                if (l.getProductId() == null || l.getQuantity() == null || l.getQuantity() <= 0) continue;
                lines.add(SaleRecordRequest.Line.builder()
                        .productId(l.getProductId())
                        .quantity(l.getQuantity().floatValue())
                        .unitPrice(l.getPrice())
                        .build());
            }
        }
        if (lines.isEmpty()) throw new ValidationException("Your cart is empty");

        // COD records no tender: the order becomes a receivable and settles on delivery, exactly as an unpaid
        // counter sale does. A CARD tender is added after the charge succeeds, by a follow-up slice — recording
        // it here would claim money we have not taken yet.
        return SaleRecordRequest.builder()
                .idempotencyKey(idempotencyKey)
                .organizationId(org)
                .channel("STOREFRONT")
                .customer(SaleRecordRequest.Customer.builder()
                        .name(dto.getCustomerName())
                        .contact(dto.getCustomerContact())
                        .address(dto.getShippingAddress())
                        .build())
                .lines(lines)
                // The two whole-document figures the books cannot derive from the lines. Both were charged to
                // the shopper and stored on the order, and neither reached the books: a coupon discount simply
                // vanished (the shopper paid 18 and was invoiced 20) and delivery income never appeared on the
                // P&L at all. The server still prices every line — these say what was taken off and added on.
                .discountTotal(dto.getDiscountAmount())
                .shippingFee(dto.getShippingFee())
                .notes("Storefront order")
                .build();
    }

    /** Reverse a sale we just created, when the rest of checkout fails. Best-effort + logged: the alternative is
     *  leaving revenue booked for an order that never completed. */
    private void reverseQuietly(Long org, String invoiceNo, String reason) {
        if (invoiceNo == null) return;
        try {
            asStore(org, () -> { tradeClient.reverseSale(invoiceNo, reason); return null; });
        } catch (RuntimeException reversalFailure) {
            LOG.error("Storefront checkout failed AND the compensating void of invoice {} failed — the books now "
                    + "carry a sale for an order that was not completed; reconcile manually", invoiceNo, reversalFailure);
        }
    }

    // OMS O1: `reserveOrThrow` / `releaseQuietly` and `OrderSagaRecoveryRelay` are DELETED. Marketplace no longer
    // runs a reservation saga — reserve, confirm, release and the recovery re-drive all happen inside
    // business-service's sale path, which is the only place a sale is authored. Keeping a second copy here was
    // what let a storefront order decrement stock without ever producing an invoice.

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    /** Run an inventory call as the storefront tenant so X-Org-Id/X-User-Id are stamped on the outbound request
     *  (the order is anonymous — there is no inbound gateway identity to forward). */
    private <T> T asStore(Long org, Supplier<T> call) {
        AtomicReference<T> out = new AtomicReference<>();
        GatewayIdentityForwarding.runAs(STOREFRONT_USER, org, () -> out.set(call.get()));
        return out.get();
    }

    /**
     * OMS O1 reconciliation: the orders that never produced an invoice.
     *
     * <p>Defaults to {@code LEGACY_UNPOSTED} — the pre-O1 backlog, which is the question anyone actually asks.
     * Any other {@code booksStatus} ({@code POSTED}, {@code REVERSED}) is accepted so the same read serves an
     * audit of what DID reach the books.
     *
     * <p>PAGED since the 2026-08-10 review (R5). The {@code LEGACY_UNPOSTED} backlog is a fixed set that only
     * shrinks, so it was the milder of the two OMS-7 stragglers — but {@code ?booksStatus=POSTED} points this
     * same query at every order the tenant has ever booked, which does not shrink at all.
     */
    @Transactional(readOnly = true)
    public com.myplus.common.web.PageResponse<OrderDTO> pageByBooksStatus(
            String booksStatus, Long orgId, Long userId, int page, int size) {
        String status = (booksStatus == null || booksStatus.isBlank())
                ? "LEGACY_UNPOSTED" : booksStatus.trim().toUpperCase();
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), com.myplus.marketplace.dto.OrderQuery.clampSize(size));
        return com.myplus.common.web.PageResponse.of(
                repo.pageByBooksStatusScoped(status, orgId, userId, pageable), this::toDTO);
    }

    // `list(orgId, userId)` — the unbounded read OMS-7 named — was DELETED 2026-08-10. O4 replaced it with
    // page() below and left it public with zero callers, which is worse than it sounds: the next person who
    // needs "all orders for this org" finds a public method that does exactly the wrong thing, uncapped, and
    // reintroduces the defect without writing a line of new SQL. `findScoped` stays on the repository — it is
    // the NULL-fallback scope every other query composes, and the unit tests assert emptiness through it.

    /**
     * OMS O4 — the back-office list: scoped, filtered, PAGED (fixes OMS-7).
     *
     * <p>Replaces {@link #list} for the Orders screen. That method loaded every order the tenant had ever taken
     * in order to show the newest 25; this one asks the database for 25.
     *
     * <p>Deliberately returns the summary projection — {@code toDTO} does not touch {@code items}, so a page
     * costs one query plus the count with no N+1 across lines. The detail view is where lines and the timeline
     * are loaded, for one order at a time.
     */
    @Transactional(readOnly = true)
    public com.myplus.common.web.PageResponse<OrderDTO> page(
            com.myplus.marketplace.dto.OrderQuery q, Long orgId, Long userId) {
        FulfilmentStatus status = null;
        if (q.getStatus() != null) {
            try {
                status = FulfilmentStatus.valueOf(q.getStatus());
            } catch (IllegalArgumentException unknown) {
                // An unknown status filters to NOTHING rather than being ignored. Silently dropping it would
                // answer a different question than the one asked — the operator would see every order and
                // believe they were looking at a filtered set.
                return new com.myplus.common.web.PageResponse<>(List.of(), q.getPage(), q.getSize(), 0, 0, true);
            }
        }
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(q.getPage(), q.getSize());
        return com.myplus.common.web.PageResponse.of(
                repo.findPage(orgId, userId, status, q.getPaymentStatus(), q.getSource(),
                        q.getFrom(), q.getTo(), q.likePattern(),
                        // O5c: "today" doubles as the late-filter switch — null means no filter, which keeps
                        // one query serving every combination rather than a second near-identical one.
                        q.isLateOnly() ? java.time.LocalDate.now() : null,
                        q.getBookedBy(),          // O7 D2: one rep's own orders, or everyone's when null
                        pageable),
                this::toDTO);
    }

    /** A storefront shopper's own orders (slice 61, My Orders). */
    public List<OrderDTO> listForCustomer(Long customerAccountId) {
        return repo.findByCustomerAccountIdOrderByCreatedAtDesc(customerAccountId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Public order tracking (slice 56): a guest looks up their order by id + contact. Returns only on a contact
     *  match (case-insensitive, non-blank) so order existence isn't revealed; minimal projection. */
    /**
     * Public order tracking (OMS-8).
     *
     * <p>Resolves by the MERCHANT-FACING number ({@code SO-000123}) plus the contact on the order. The old form
     * took the raw auto-increment primary key and called an UNSCOPED {@code findById}: sequential, so the id
     * space could be walked, and meaningless to a customer on the phone.
     *
     * <p>Still deliberately not org-scoped — a guest has no tenant identity — but the pairing of a per-org
     * number with a contact check is what makes it safe, where a global id was not.
     *
     * <p><b>Legacy numeric ref accepted for one release.</b> Links already emailed to customers carry
     * {@code ?ref=123}. Dropping them the day this ships would break every one of those; they are logged at WARN
     * so the tail is visible before the fallback is removed.
     */
    public com.myplus.marketplace.dto.OrderTrackDTO trackPublic(String ref, String contact) {
        Order o = null;
        String r = ref == null ? "" : ref.trim();
        if (!r.isEmpty()) {
            if (r.regionMatches(true, 0, com.myplus.commerce.domain.InvoiceNumbers.ORDER_PREFIX, 0,
                                com.myplus.commerce.domain.InvoiceNumbers.ORDER_PREFIX.length())) {
                // Per-org numbering means SO-000001 exists in every tenant. The contact was always the security
                // check; it is now also what picks the right one, which keeps this working without giving an
                // anonymous guest a tenant identity they do not have.
                String want = contact == null ? "" : contact.trim();
                for (Order candidate : repo.findAllByOrderNo(r.toUpperCase())) {
                    if (candidate.getCustomerContact() != null
                            && candidate.getCustomerContact().trim().equalsIgnoreCase(want)) {
                        o = candidate;
                        break;
                    }
                }
            } else if (r.chars().allMatch(Character::isDigit)) {
                LOG.warn("Legacy numeric tracking ref '{}' used — this fallback is removed after one release", r);
                try { o = repo.findById(Long.valueOf(r)).orElse(null); } catch (NumberFormatException ignored) { }
            }
        }
        String c = contact == null ? "" : contact.trim();
        if (o == null || c.isEmpty() || o.getCustomerContact() == null
                || !o.getCustomerContact().trim().equalsIgnoreCase(c)) {
            throw new ResourceNotFoundException("No order found for that reference and contact.");
        }
        java.util.List<com.myplus.marketplace.dto.OrderTrackDTO.Event> timeline = new ArrayList<>();
        for (com.myplus.marketplace.entity.OrderEvent e : orderEventRepository.findByOrderIdOrderByCreatedAtAsc(o.getId())) {
            timeline.add(new com.myplus.marketplace.dto.OrderTrackDTO.Event(e.getStatus(), e.getCreatedAt()));
        }
        // OMS O5b: the parcels, so a half-delivered order can say what is on its way and under what tracking
        // number rather than just reading PARTIALLY_SHIPPED at the customer.
        java.util.List<com.myplus.marketplace.dto.OrderTrackDTO.Parcel> parcels = new ArrayList<>();
        for (com.myplus.marketplace.dto.ShipmentDTO s : shipmentService.forOrder(o.getId())) {
            int units = 0;
            if (s.getLines() != null)
                for (com.myplus.marketplace.dto.ShipmentDTO.Line l : s.getLines())
                    units += l.getQuantity() == null ? 0 : l.getQuantity();
            parcels.add(new com.myplus.marketplace.dto.OrderTrackDTO.Parcel(
                    s.getShipmentNo(), s.getCarrier(), s.getTrackingNumber(), s.getShippedAt(), units));
        }

        return new com.myplus.marketplace.dto.OrderTrackDTO(
                // The NUMBER, not the id. Pre-O2 rows are backfilled by V11, so the fallback is belt-and-braces.
                o.getOrderNo() != null ? o.getOrderNo() : String.valueOf(o.getId()),
                o.getCustomerName(),
                o.getFulfilmentStatus() != null ? o.getFulfilmentStatus().name() : null,
                o.getCreatedAt(), o.getTotal(), timeline, parcels);
    }

    /**
     * One order, in full (OMS O4).
     *
     * <p>The summary {@link #toDTO} plus the three things the back office could not previously see: the lines
     * (what was actually sold), the {@code order_events} timeline (written on every status change since slice 46
     * and shown only to the SHOPPER until now — the merchant saw less about their own order than the customer
     * did), and the transitions this order may legally make.
     *
     * <p>Scoped by {@code findByIdScoped}, so another tenant's order is indistinguishable from a missing one.
     */
    @Transactional(readOnly = true)
    public OrderDTO get(Long id, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        // O7 D1: the line mapping this used to do inline is now toDTOWithLines, shared with the review actions.
        // It was the same fifteen lines twice, and the copies would have drifted the first time a line gained a
        // field — the shape of defect O4 removed from the browser's rival transition map.
        OrderDTO d = toDTOWithLines(o);
        List<OrderDTO.Line> lines = d.getItems();

        List<OrderDTO.Event> timeline = new ArrayList<>();
        for (com.myplus.marketplace.entity.OrderEvent e : orderEventRepository.findByOrderIdOrderByCreatedAtAsc(id))
            timeline.add(new OrderDTO.Event(e.getStatus(), e.getNote(), e.getCreatedAt()));
        d.setTimeline(timeline);

        // OMS O5b — the parcels. Names are filled in from the lines already loaded above rather than re-read,
        // so opening an order costs no extra query per shipment line.
        java.util.Map<Long, String> nameByLine = new java.util.HashMap<>();
        for (OrderDTO.Line l : lines) nameByLine.put(l.getId(), l.getProductName());
        List<com.myplus.marketplace.dto.ShipmentDTO> shipments = shipmentService.forOrder(id);
        for (com.myplus.marketplace.dto.ShipmentDTO s : shipments) {
            if (s.getLines() == null) continue;
            for (com.myplus.marketplace.dto.ShipmentDTO.Line sl : s.getLines())
                sl.setProductName(nameByLine.get(sl.getOrderItemId()));
        }
        d.setShipments(shipments);

        return d;
    }

    /**
     * Move an order's fulfilment status — the ONE guarded write path (OMS-2).
     *
     * <p>Before O2 this accepted ANY transition: an order could go CANCELLED → SHIPPED, i.e. goods dispatched
     * against an order whose money and stock had already been reversed. The move is now checked against
     * {@link FulfilmentStatus#canMoveTo} — a whitelist, because the failure mode of a missed illegal transition
     * is shipping something that was cancelled.
     *
     * <p>WHO may make the move is the controller's business ({@code @PreAuthorize}); WHICH moves exist is this
     * method's. Keeping them apart means a second entry point cannot accidentally get different rules.
     */
    @Transactional
    public OrderDTO updateStatus(Long id, String status, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        FulfilmentStatus s;
        try { s = FulfilmentStatus.valueOf(status == null ? "" : status.trim().toUpperCase()); }
        catch (Exception e) { throw new ValidationException("Invalid status: " + status); }

        // OMS O5b: SHIPPED and PARTIALLY_SHIPPED are DERIVED from what actually went out. Setting them here
        // would let the header claim a dispatch no parcel accounts for — the disagreement the projection exists
        // to make impossible.
        //
        // Checked BEFORE the whitelist, deliberately. Behind it, the generic "A NEW order cannot become SHIPPED"
        // fired first and the caller was told the move was out of sequence rather than that it is not a move at
        // all — technically a refusal, but pointing at the wrong fix.
        if (s.isDerived())
            throw new ValidationException("An order becomes " + s + " by recording a shipment, not by being "
                    + "marked. Use Ship (POST /orders/{id}/shipments) so the parcel, carrier and tracking are "
                    + "recorded with it.");

        FulfilmentStatus current = o.getFulfilmentStatus();

        // OMS O7 D1 — the approval decisions are NOT generic status moves, for the same reason O5b took the
        // derived states out of here: this endpoint's authority check gates REVERSALS (admin) and lets
        // everything else through as shop-floor work. Releasing a booker's order is neither — it is the control
        // the whole pre-sales model rests on, and reaching it through the generic endpoint would bypass both the
        // rejection REASON and the confirm gate. Named endpoints, so the refusal points at the right fix.
        if (current == FulfilmentStatus.PENDING_APPROVAL
                && (s == FulfilmentStatus.NEW || s == FulfilmentStatus.REJECTED)) {
            throw new ValidationException("Confirming or rejecting a booked order is a review decision, not a "
                    + "status change. Use POST /orders/{id}/confirm or POST /orders/{id}/reject (which records "
                    + "the reason the booker needs).");
        }
        if (current == FulfilmentStatus.REJECTED && s == FulfilmentStatus.PENDING_APPROVAL)
            throw new ValidationException("Use POST /orders/{id}/resubmit to send a revised order back for "
                    + "review, so the revision is recorded.");

        // Asking for the state it is already in is a no-op, not an error: a double-click on "Ship" must not fail.
        if (current != s) {
            if (current != null && !current.canMoveTo(s))
                throw new ValidationException("A " + current + " order cannot become " + s + ".");
        }

        // E7 cancel (slice 51): transitioning INTO CANCELLED reverses the order. Idempotent — only on the FIRST
        // transition, so re-cancelling never reverses twice.
        //
        // O1 changed what "has something to reverse" means. This used to require `reservationId != null`, because
        // a marketplace-held inventory reservation was the only thing a cancel could undo. Now the storefront
        // records a SALE and holds no reservation of its own, so that guard silently skipped every new order —
        // stock stayed decremented and the revenue stayed booked. The question is "is there anything to reverse?":
        // an INVOICE (post-O1) or a RESERVATION (pre-O1 orders, which still exist in live data).
        boolean nowCancelling = s == FulfilmentStatus.CANCELLED && o.getFulfilmentStatus() != FulfilmentStatus.CANCELLED;
        boolean hasSomethingToReverse = hasText(o.getInvoiceNo()) || o.getReservationId() != null;
        if (nowCancelling && hasSomethingToReverse && !o.getItems().isEmpty()) {
            returnStockQuietly(o);
        }
        // O7 D1c — separate from the reversal above, and deliberately NOT behind the same guard. That one asks
        // "was anything SOLD to undo"; this asks "is anything still PROMISED". A confirmed order that was
        // never dispatched has a hold and no invoice, so it fails that test while still holding stock.
        if (nowCancelling) orderStockHoldService.release(o, orgId);

        o.setFulfilmentStatus(s);
        Order saved = repo.save(o);
        notificationService.notify(saved, s.name(), "Status updated to " + s.name());   // slice 57: timeline event
        return toDTO(saved);
    }

    /** Refund a card-paid order (E6, slice 70), full or partial, via the payment provider. Caps at the remaining
     *  refundable amount; flips paymentStatus to PARTIALLY_REFUNDED / REFUNDED. Org-scoped (anti-IDOR). */
    @Transactional
    public OrderDTO refund(Long id, BigDecimal amount, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!isCardRefundable(o))
            throw new ValidationException("Only a card-paid order can be refunded (COD is settled in cash)");
        if (!doRefund(o, amount))
            throw new ValidationException("Order is already fully refunded");
        Order saved = repo.save(o);
        notificationService.notify(saved, "REFUNDED", "Refund issued");   // timeline (slice 57)
        return toDTO(saved);
    }

    /** True when the order was paid by card and still has refundable balance left. */
    private boolean isCardRefundable(Order o) {
        return ("PAID".equalsIgnoreCase(o.getPaymentStatus()) || "PARTIALLY_REFUNDED".equalsIgnoreCase(o.getPaymentStatus()))
                && o.getPaymentRef() != null;
    }

    /** Apply a refund to a card-refundable order (caller checked {@link #isCardRefundable}); caps at the remaining
     *  amount, calls the gateway, updates amount + status. Returns false if nothing was left to refund. */
    private boolean doRefund(Order o, BigDecimal amount) {
        BigDecimal total = o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO;
        BigDecimal already = o.getRefundedAmount() != null ? o.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = total.subtract(already);
        if (remaining.signum() <= 0) return false;
        BigDecimal amt = (amount == null || amount.signum() <= 0) ? remaining : amount.min(remaining);
        PaymentGateway.Refund r = paymentGateway.refund(o.getPaymentRef(), amt);
        if (!r.success()) throw new ValidationException("Refund failed: " + (r.reason() != null ? r.reason() : "unknown"));
        BigDecimal newRefunded = already.add(amt);
        o.setRefundedAmount(newRefunded);
        o.setRefundRef(r.refundId());
        o.setPaymentStatus(newRefunded.compareTo(total) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED");
        return true;
    }

    /** Shopper requests a return (E10, slice 71) — public, verified by order id + contact (slice 56 pattern). Only a
     *  DELIVERED order is returnable. Sets RETURN_REQUESTED + reason for the back-office to process. */
    @Transactional
    public OrderTrackDTO requestReturn(Long ref, String contact, String reason) {
        Order o = (ref == null) ? null : repo.findById(ref).orElse(null);
        String c = contact == null ? "" : contact.trim();
        if (o == null || c.isEmpty() || o.getCustomerContact() == null
                || !o.getCustomerContact().trim().equalsIgnoreCase(c)) {
            throw new ResourceNotFoundException("No order found for that reference and contact.");
        }
        if (o.getFulfilmentStatus() != FulfilmentStatus.DELIVERED)
            throw new ValidationException("Only a delivered order can be returned");
        o.setFulfilmentStatus(FulfilmentStatus.RETURN_REQUESTED);
        o.setReturnReason(reason);
        Order saved = repo.save(o);
        notificationService.notify(saved, "RETURN_REQUESTED", "Return requested");
        // Track by the order's OWN number now that one exists (falls back to the id for pre-O2 rows).
        return trackPublic(saved.getOrderNo() != null ? saved.getOrderNo() : String.valueOf(saved.getId()), c);
    }

    /** Back-office processes a return (E10, slice 71): return stock to inventory (G2 inverse saga) + refund a card
     *  order (best-effort) → RETURNED. From RETURN_REQUESTED or DELIVERED (admin-initiated). Org-scoped. */
    @Transactional
    public OrderDTO processReturn(Long id, Long orgId, Long userId) {
        Order o = repo.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        FulfilmentStatus s = o.getFulfilmentStatus();
        if (s != FulfilmentStatus.RETURN_REQUESTED && s != FulfilmentStatus.DELIVERED)
            throw new ValidationException("Only a delivered / return-requested order can be returned");

        // Same O1 correction as the cancel path: an invoice OR a legacy reservation means there is something to
        // reverse. A returned order that only checked reservationId would silently keep the revenue booked.
        if ((hasText(o.getInvoiceNo()) || o.getReservationId() != null) && !o.getItems().isEmpty()) {
            returnStockQuietly(o);                          // stock back (+ books, post-O1)
        }
        if (isCardRefundable(o)) {
            try { doRefund(o, null); }                       // full remaining refund, best-effort
            catch (RuntimeException refundFailure) {
                LOG.warn("Return {} processed (stock returned) but refund failed; reconcile manually", o.getId(), refundFailure);
            }
        }
        o.setFulfilmentStatus(FulfilmentStatus.RETURNED);
        Order saved = repo.save(o);
        notificationService.notify(saved, "RETURNED", "Return processed");   // timeline (slice 57)
        return toDTO(saved);
    }

    /**
     * Reverse a cancelled order.
     *
     * <p><b>OMS O1 changed what this means.</b> Before O1 a storefront order had no invoice, so returning stock
     * was the whole reversal. Now it has one, and returning stock alone would leave the revenue booked —
     * overstating P&amp;L and the tax register, which is the same defect O1 exists to remove, pointing the other
     * way. So a POSTED order is reversed by VOIDING its invoice, which restores the stock, refunds whatever was
     * paid, zeroes the invoice in place and posts the GL reversal — one operation, business-service's own.
     *
     * <p>A pre-fulfilment cancellation is a void rather than a credit note: nothing shipped, so there is nothing
     * to credit back.
     *
     * <p>Orders placed BEFORE O1 (`LEGACY_UNPOSTED`, no invoice) still take the old stock-only path — they have
     * no sale to reverse.
     *
     * <p>Best-effort + logged either way: a failure leaves the order cancelled rather than blocking the
     * cancellation, but a failed void is logged at ERROR because the books are then wrong until someone looks.
     */
    private void returnStockQuietly(Order o) {
        if (o.getInvoiceNo() != null && !o.getInvoiceNo().isBlank()) {
            try {
                asStore(o.getOrganizationId(),
                        () -> { tradeClient.reverseSale(o.getInvoiceNo(), "Order cancelled"); return null; });
                o.setBooksStatus("REVERSED");
            } catch (RuntimeException reversalFailure) {
                LOG.error("Order {} cancelled but voiding invoice {} failed — stock is NOT back and the revenue "
                        + "is still booked; reconcile manually", o.getId(), o.getInvoiceNo(), reversalFailure);
            }
            return;
        }

        // Pre-O1 order: no invoice exists, so inventory is the only thing to put back.
        List<StockReturnLine> lines = new ArrayList<>();
        for (OrderItem it : o.getItems()) {
            if (it.getProductId() == null || it.getQuantity() == null || it.getQuantity() <= 0) continue;
            lines.add(new StockReturnLine(it.getProductId(), it.getQuantity().floatValue()));
        }
        if (lines.isEmpty()) return;
        try {
            inventoryClient.returnStock(o.getReservationId(), new StockReturnRequest(lines));
        } catch (RuntimeException returnFailure) {
            LOG.warn("Order {} cancelled but stock-return for reservation {} failed; reconcile manually",
                    o.getId(), o.getReservationId(), returnFailure);
        }
    }

    private List<OrderItem> toItems(List<OrderDTO.Line> lines) {
        List<OrderItem> items = new ArrayList<>();
        if (lines == null) return items;
        for (OrderDTO.Line l : lines) {
            if (l.getProductId() == null) continue;
            items.add(OrderItem.builder()
                    .productId(l.getProductId())
                    // O4: snapshot the name the caller already holds. The storefront cart carries it and the
                    // checkout used to discard it, which is why order detail could only say "Product 42".
                    .productName(l.getProductName())
                    // O5c: the line records what was ORDERED. On the way in `quantity` is only what is being
                    // INVOICED now, so the ordered figure is invoiced + owed. Storing the invoiced number as the
                    // ordered one would lose the shortfall, and the order would read complete while still owing.
                    .quantity(nzInt(l.getQuantity()) + nzInt(l.getQuantityBackordered()))
                    .quantityBackordered(nzInt(l.getQuantityBackordered()))
                    .price(l.getPrice())
                    .discount(l.getDiscount())      // the rep's per-line concession, carried onto the order
                    .build());
        }
        return items;
    }

    private static int nzInt(Integer v) { return v == null ? 0 : v; }

    /**
     * O7 D1 — the order WITH its lines, for the review actions (book / amend / confirm / reject / resubmit).
     *
     * <h3>Why not just put lines in {@link #toDTO}</h3>
     * Because {@code toDTO} also maps every row of the paged back-office list, and O4 deliberately kept lines
     * out of it: a page of 25 would become 25 extra queries, which is the N+1 that pagination exists to avoid.
     *
     * <h3>Why the review actions need them anyway</h3>
     * The reviewer's whole job is the lines — an amend response that does not say what the lines now are cannot
     * drive the screen that just changed them, and the caller would have to re-read the order to find out what
     * its own write did. One order, already loaded in this transaction, so there is no N+1 here to avoid.
     */
    private OrderDTO toDTOWithLines(Order o) {
        OrderDTO d = toDTO(o);
        List<OrderDTO.Line> lines = new ArrayList<>();
        for (OrderItem it : o.getItems()) {
            OrderDTO.Line l = new OrderDTO.Line();
            l.setId(it.getId());                     // O5b: shipping is requested per LINE
            l.setProductId(it.getProductId());
            l.setProductName(it.getProductName());   // null for pre-V14 rows; the UI falls back to the id
            l.setQuantity(it.getQuantity());
            // Normalised to 0, not passed through as null: the Ship form does arithmetic on these
            // (outstanding = quantity − shipped), and a null there reads as NaN in the browser.
            l.setQuantityShipped(nzInt(it.getQuantityShipped()));
            l.setQuantityBackordered(nzInt(it.getQuantityBackordered()));
            l.setPrice(it.getPrice());
            l.setDiscount(it.getDiscount());   // so the review screen shows what the rep gave away
            lines.add(l);
        }
        d.setItems(lines);
        return d;
    }

    private OrderDTO toDTO(Order o) {
        OrderDTO d = new OrderDTO();
        d.setId(o.getId());
        d.setOrganizationId(o.getOrganizationId());
        d.setInvoiceNo(o.getInvoiceNo());
        d.setOrderNo(o.getOrderNo());           // O2: SO-000123
        d.setBooksStatus(o.getBooksStatus());   // O1: POSTED | LEGACY_UNPOSTED | REVERSED
        d.setCustomerName(o.getCustomerName());
        d.setCustomerContact(o.getCustomerContact());
        d.setTotal(o.getTotal());
        d.setSubTotal(o.getSubTotal());
        d.setTaxTotal(o.getTaxTotal());
        d.setShippingFee(o.getShippingFee());
        d.setShippingMethod(o.getShippingMethod());
        d.setCouponCode(o.getCouponCode());
        d.setDiscountAmount(o.getDiscountAmount());
        d.setFulfilmentStatus(o.getFulfilmentStatus() != null ? o.getFulfilmentStatus().name() : null);
        d.setSource(o.getSource());
        d.setPaymentMode(o.getPaymentMode());
        d.setPaymentStatus(o.getPaymentStatus());
        d.setPaymentRef(o.getPaymentRef());
        d.setRefundRef(o.getRefundRef());
        d.setRefundedAmount(o.getRefundedAmount());
        d.setReservationId(o.getReservationId());
        d.setReservationStatus(o.getReservationStatus());
        d.setRejectionReason(o.getRejectionReason());   // O7 D1 — the booker's only route to fixing it
        d.setBookedByUserId(o.getBookedByUserId());     // O7 D2 — attribution
        d.setBookedByName(o.getBookedByName());
        d.setCustomerId(o.getCustomerId());             // O7 D2c — which trade account this bills
        d.setShippingAddress(o.getShippingAddress());
        d.setReturnReason(o.getReturnReason());
        d.setCreatedAt(o.getCreatedAt());

        // O5c: promised date + lateness. Derived on read — a stored "late" flag is wrong the moment the clock
        // moves past it, and would need a job to keep true.
        d.setPromisedDate(o.getPromisedDate());
        FulfilmentStatus fs = o.getFulfilmentStatus();
        boolean complete = fs == FulfilmentStatus.DELIVERED || fs == FulfilmentStatus.CANCELLED
                || fs == FulfilmentStatus.RETURNED || fs == FulfilmentStatus.SHIPPED;
        d.setLate(o.getPromisedDate() != null && !complete
                && o.getPromisedDate().isBefore(java.time.LocalDate.now()));

        // OMS O4: the server says what may happen next, so the browser stops keeping its own (drifted) copy.
        // On the LIST too, not just the detail — the list draws action buttons, and that is exactly where the
        // phantom Cancel on a SHIPPED order was being rendered.
        d.setAllowedTransitions(o.getFulfilmentStatus() == null
                ? List.of() : o.getFulfilmentStatus().allowedTransitionNames());

        // What is still refundable. Derived here because the refund dialog defaults to it and OrderService.refund
        // rejects an over-refund — two derivations of one number is how a UI offers an amount the server refuses.
        java.math.BigDecimal total = o.getTotal() == null ? java.math.BigDecimal.ZERO : o.getTotal();
        java.math.BigDecimal refunded = o.getRefundedAmount() == null ? java.math.BigDecimal.ZERO : o.getRefundedAmount();
        java.math.BigDecimal refundable = total.subtract(refunded);
        d.setRefundableAmount(refundable.signum() < 0 ? java.math.BigDecimal.ZERO : refundable);

        return d;
    }
}
