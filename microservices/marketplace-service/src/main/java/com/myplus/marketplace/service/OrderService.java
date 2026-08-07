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
    private final NotificationService notificationService;
    private final com.myplus.marketplace.repository.OrderEventRepository orderEventRepository;
    private final com.myplus.marketplace.repository.StorefrontCustomerRepository customerRepo;
    private final CartService cartService;   // slice 68: close the persistent cart on successful checkout
    private final ShipmentService shipmentService;   // O5b: the parcels an order has gone out in
    /** O5c — one resolver shared with the quote, so what the shopper is told matches what is invoiced. */
    private final BackorderPolicy backorderPolicy;

    @Transactional
    public OrderDTO record(OrderDTO dto, Long orgId, Long userId) {
        Order o = Order.builder()
                .organizationId(orgId).userId(userId)
                .invoiceNo(dto.getInvoiceNo())
                .customerName(dto.getCustomerName())
                .total(dto.getTotal())
                .shippingAddress(dto.getShippingAddress())
                .source("POS").paymentMode(dto.getPaymentMode() != null ? dto.getPaymentMode() : "POS")
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
        Order saved = repo.save(o);
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
                .fulfilmentStatus(FulfilmentStatus.NEW)
                .build();
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
        if (failure instanceof org.springframework.web.client.RestClientResponseException http) {
            try {
                String body = http.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    com.fasterxml.jackson.databind.JsonNode node =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                    String msg = node.path("message").asText(null);
                    if (msg != null && !msg.isBlank()) return "Sorry — " + msg;
                }
            } catch (Exception ignored) {
                // A non-JSON body is not worth failing over; fall through to the generic wording.
            }
        }
        return "Sorry, an item in your cart is out of stock or unavailable.";
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
     */
    public List<OrderDTO> listByBooksStatus(String booksStatus, Long orgId, Long userId) {
        String status = (booksStatus == null || booksStatus.isBlank())
                ? "LEGACY_UNPOSTED" : booksStatus.trim().toUpperCase();
        return repo.findByBooksStatusScoped(status, orgId, userId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<OrderDTO> list(Long orgId, Long userId) {
        return repo.findScoped(orgId, userId).stream().map(this::toDTO).collect(Collectors.toList());
    }

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
                        q.getFrom(), q.getTo(), q.likePattern(), pageable),
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
        OrderDTO d = toDTO(o);

        List<OrderDTO.Line> lines = new ArrayList<>();
        for (OrderItem it : o.getItems()) {
            OrderDTO.Line l = new OrderDTO.Line();
            l.setId(it.getId());                     // O5b: shipping is requested per LINE
            l.setProductId(it.getProductId());
            l.setProductName(it.getProductName());   // null for pre-V14 rows; the UI falls back to the id
            l.setQuantity(it.getQuantity());
            l.setQuantityShipped(it.getQuantityShipped() == null ? 0 : it.getQuantityShipped());
            l.setQuantityBackordered(it.getQuantityBackordered() == null ? 0 : it.getQuantityBackordered());
            l.setPrice(it.getPrice());
            lines.add(l);
        }
        d.setItems(lines);

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
                    .price(l.getPrice()).build());
        }
        return items;
    }

    private static int nzInt(Integer v) { return v == null ? 0 : v; }

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
