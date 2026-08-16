package com.myplus.marketplace.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.OrderQuery;
import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Orders back-office (E1, slice 46). Mapped at {@code /orders} → {@code /api/marketplace/orders} via the gateway
 * (StripPrefix=2). Tenant-scoped via CurrentUser.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    /** OMS O5b — recording a dispatch, which is what moves an order into a shipped state. */
    private final com.myplus.marketplace.service.ShipmentService shipmentService;
    /** O7 D4 — what happened at the shop door, keyed by the admin from the signed invoice. */
    private final com.myplus.marketplace.service.DeliveryService deliveryService;

    @PostMapping
    public ApiResponse<OrderDTO> record(@RequestBody OrderDTO dto) {
        return ApiResponse.success(orderService.record(dto, CurrentUser.organizationId(), CurrentUser.userId()), "Order recorded");
    }

    /**
     * The back-office list — paginated and filtered (OMS O4, fixes OMS-7).
     *
     * <p>This used to be {@code orderService.list(...)}: every order the tenant had ever taken, unpaginated and
     * unfiltered, mapped to DTOs and shipped to a browser that rendered all of them. A merchant with 20 000
     * orders paid for 20 000 rows to look at the newest 25.
     *
     * <p>All parameters are optional, so an unqualified {@code GET /orders} still works — it returns the first
     * page instead of everything. {@code size} is clamped in {@link OrderQuery} (max {@value
     * com.myplus.marketplace.dto.OrderQuery#MAX_SIZE}); a client cannot ask for the unbounded read back.
     */
    @GetMapping
    public ApiResponse<com.myplus.common.web.PageResponse<OrderDTO>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String source,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate to,
            @RequestParam(required = false) String q,
            /** OMS O5c — only orders promised before today and not yet complete. */
            @RequestParam(required = false, defaultValue = "false") boolean late,
            /**
             * OMS O7 D2 — {@code mine=true} narrows to the caller's OWN booked orders.
             *
             * <p>A boolean resolved to the caller's id here, deliberately, rather than a {@code bookedBy=<id>}
             * parameter: a rep asking "what happened to my orders?" should not be able to ask the same question
             * about a colleague by editing a number in the URL. The question a client may ask is "mine"; whose
             * that is, is the server's to decide.
             */
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        OrderQuery query = OrderQuery.of(page, size, status, paymentStatus, source, from, to, q, late,
                mine ? CurrentUser.userId() : null);
        return ApiResponse.success(orderService.page(query, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /**
     * OMS O1 reconciliation — orders that never reached the books.
     *
     * <p>{@code GET /orders/reconciliation} lists the {@code LEGACY_UNPOSTED} backlog: storefront orders placed
     * before O1, which moved stock and possibly charged a card but produced no invoice, so they are missing from
     * the P&amp;L, tax register and AR. They are not back-posted (that would write into closed periods), so this
     * is how an operator finds them. Pass {@code ?booksStatus=POSTED} to audit the other side.
     *
     * <p>Owner/admin-gated: it is a financial-integrity view, not a shop-floor one.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @GetMapping("/reconciliation")
    public ApiResponse<com.myplus.common.web.PageResponse<OrderDTO>> reconciliation(
            @RequestParam(required = false) String booksStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(orderService.pageByBooksStatus(booksStatus,
                CurrentUser.organizationId(), CurrentUser.userId(),
                page == null ? 0 : page, size == null ? 0 : size));
    }

    /**
     * OMS O5c — what this shop still owes, and what it can now fill.
     *
     * <p>{@code ?ready=true} narrows to orders whose outstanding lines have stock again. Not a sweeper and not
     * an allocator: it shows the merchant the choice, and O5b's Ship action carries it out.
     *
     * <p>PAGED since the 2026-08-10 review (R5) — a shop's backorder book grows with its trade, so this was the
     * unbounded read that would actually have bitten. {@code ready} filters the page rather than the query,
     * because readiness lives in inventory and cannot be a SQL predicate; see the service method for why that
     * is the honest trade rather than a bug.
     */
    @GetMapping("/backorders")
    public ApiResponse<com.myplus.common.web.PageResponse<OrderDTO>> backorders(
            @RequestParam(required = false, defaultValue = "false") boolean ready,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(orderService.backordersOutstanding(
                CurrentUser.organizationId(), CurrentUser.userId(), ready,
                page == null ? 0 : page, size == null ? 0 : size));
    }

    // ── OMS O7 D1 — distribution pre-sales: book → review → confirm / reject ──────────────────────────────

    /**
     * An order booker books an order at the outlet.
     *
     * <p>Creates a {@code PENDING_APPROVAL} order with <b>no invoice and no stock movement</b> — it is a
     * request, not a sale. The invoice is raised at dispatch, from what actually leaves (§6 D-1).
     *
     * <p>No extra privilege gate yet: every authenticated user of the tenant may book, exactly as every
     * authenticated user may place a POS order today. The dedicated {@code ROLE_ORDER_BOOKER} — which
     * <em>restricts</em> a booker to booking and reading their own orders rather than granting anything — is
     * <b>D2</b>, because a role that nobody is assigned to gates nothing.
     */
    @PostMapping("/booking")
    public ApiResponse<OrderDTO> book(@RequestBody OrderDTO dto) {
        return ApiResponse.success(
                orderService.book(dto, CurrentUser.organizationId(), CurrentUser.userId(), CurrentUser.email()),
                "Order booked");
    }

    /**
     * Amend an order that is still under review (D-2, D-3).
     *
     * <p>Lines, quantities, prices, discount, promised date and the outlet's details. Refused once the order is
     * confirmed — at that point it is a picking instruction, and past dispatch it is an invoice.
     *
     * <p>A concurrent edit surfaces as <b>409</b> (the {@code @Version} O2 added, mapped by the shared handler
     * since the 2026-08-10 review) rather than one reviewer silently overwriting the other — the case D-2
     * created by letting both the booker and the admin edit.
     */
    @PutMapping("/{id}")
    public ApiResponse<OrderDTO> amend(@PathVariable Long id, @RequestBody OrderDTO dto) {
        return ApiResponse.success(orderService.amend(id, dto,
                CurrentUser.organizationId(), CurrentUser.userId(), CurrentUser.email()), "Order amended");
    }

    /**
     * The warehouse admin releases a booked order to the floor.
     *
     * <p>Gated at {@code ADMIN_PRIVILEGE}: this is the control the whole pre-sales model rests on — the point
     * at which one person's proposal becomes the company's commitment — so it carries the same authority as the
     * other decisions that move money and stock. A booker confirming their own orders would dissolve the
     * segregation of duties that makes the model auditable.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @PostMapping("/{id}/confirm")
    public ApiResponse<OrderDTO> confirm(@PathVariable Long id) {
        return ApiResponse.success(
                orderService.confirm(id, CurrentUser.organizationId(), CurrentUser.userId()), "Order confirmed");
    }

    /** Reject a booked order. The reason is required — without it the booker cannot fix the order. */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @PostMapping("/{id}/reject")
    public ApiResponse<OrderDTO> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success(orderService.reject(id, body == null ? null : body.get("reason"),
                CurrentUser.organizationId(), CurrentUser.userId()), "Order rejected");
    }

    /**
     * Send a revised order back for review.
     *
     * <p>Deliberately NOT admin-gated: D-2 settled that the booker may revise their own rejected order, and
     * requiring an admin to resubmit would put the reviewer back in the loop for the very work that was handed
     * back. The admin still decides the outcome — resubmitting only returns it to the queue.
     */
    @PostMapping("/{id}/resubmit")
    public ApiResponse<OrderDTO> resubmit(@PathVariable Long id) {
        return ApiResponse.success(
                orderService.resubmit(id, CurrentUser.organizationId(), CurrentUser.userId()), "Resubmitted for review");
    }

    /**
     * OMS O7 D4 — record what happened when a parcel reached the shop.
     *
     * <p>Keyed by the warehouse admin from the signed paper invoice the driver brings back (§6 D-5 — no
     * device). Per-line delivered quantities; anything short is credited against the invoice that parcel went
     * out on, and the settlement reaches the same AR ledger the counter uses.
     *
     * <p>Gated at {@code ADMIN_PRIVILEGE}: it raises credit notes and takes money, which is the same class of
     * action as {@code /refund} and {@code /return}.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @PostMapping("/{id}/delivery")
    public ApiResponse<com.myplus.marketplace.dto.DeliveryDTO> recordDelivery(
            @PathVariable Long id, @RequestBody com.myplus.marketplace.dto.DeliveryDTO body) {
        return ApiResponse.success(deliveryService.record(id, body,
                CurrentUser.organizationId(), CurrentUser.userId(), CurrentUser.email()), "Delivery recorded");
    }

    /**
     * What has been keyed against this order's parcels, oldest first.
     *
     * <p>D5: a DTO, not the entity. This answered with {@code List<DeliveryRecord>} — the §1.5 breach D1 caught
     * and fixed once already — which put {@code organizationId} and the raw row id on the wire. Same field
     * names, plus the remittance state a collection now carries.
     */
    @GetMapping("/{id}/deliveries")
    public ApiResponse<java.util.List<com.myplus.marketplace.dto.DeliveryRecordDTO>> deliveries(@PathVariable Long id) {
        return ApiResponse.success(
                deliveryService.forOrder(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /** Who changed what on this order, and why — oldest first. */
    @GetMapping("/{id}/amendments")
    public ApiResponse<java.util.List<com.myplus.marketplace.dto.OrderAmendmentDTO>> amendments(@PathVariable Long id) {
        return ApiResponse.success(
                orderService.amendments(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDTO> get(@PathVariable Long id) {
        return ApiResponse.success(orderService.get(id, CurrentUser.organizationId(), CurrentUser.userId()));
    }

    /**
     * Move an order's fulfilment status (OMS-2).
     *
     * <h3>Authority</h3>
     * Before O2 this endpoint had NO gate at all — any authenticated user of any role could mark an order
     * DELIVERED, which is the state a return is judged against. It is now split by what the move actually does:
     *
     * <ul>
     *   <li><b>CANCELLED / RETURNED reverse money and stock</b> — they trigger the O1 void, so they carry the
     *       same {@code ADMIN_PRIVILEGE} gate as {@code /refund} and {@code /return} below. Anything else would
     *       be inconsistent: refusing a refund but allowing a cancel that refunds is a gate in name only.</li>
     *   <li><b>PACKED / SHIPPED / DELIVERED is shop-floor work</b> — any authenticated staff user, because
     *       requiring an admin to pack a box would put the gate where the risk is not.</li>
     * </ul>
     *
     * <p>The check is here rather than in the service because it is about the CALLER; which transitions exist at
     * all is the service's rule and applies to every entry point.
     */
    @PutMapping("/{id}/status")
    public ApiResponse<OrderDTO> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String requested = body == null ? null : body.get("status");
        requireAuthorityFor(requested);
        return ApiResponse.success(
                orderService.updateStatus(id, requested, CurrentUser.organizationId(), CurrentUser.userId()), "Status updated");
    }

    /** A reversal (CANCELLED/RETURNED) needs ADMIN_PRIVILEGE; forward fulfilment does not. */
    private void requireAuthorityFor(String requested) {
        FulfilmentStatus target;
        try { target = FulfilmentStatus.valueOf(requested == null ? "" : requested.trim().toUpperCase()); }
        catch (Exception e) { return; }   // unknown value — let the service produce the "Invalid status" message
        if (!target.isReversal()) return;

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ADMIN_PRIVILEGE".equals(a.getAuthority())
                        || "ROLE_OWNER".equals(a.getAuthority())
                        || "SUPER_PRIVILEGE".equals(a.getAuthority()));
        if (!allowed)
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cancelling or returning an order reverses money and stock — that needs an admin.");
    }

    /** Back-office refund (E6, slice 70). {@code amount} optional — omitted/0 = full remaining refund. */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")   // a refund moves money — admin/owner only
    @PostMapping("/{id}/refund")
    public ApiResponse<OrderDTO> refund(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        java.math.BigDecimal amount = null;
        Object a = body == null ? null : body.get("amount");
        if (a != null && !a.toString().isBlank()) amount = new java.math.BigDecimal(a.toString());
        return ApiResponse.success(
                orderService.refund(id, amount, CurrentUser.organizationId(), CurrentUser.userId()), "Refund issued");
    }

    /**
     * OMS O5b — dispatch part or all of an order.
     *
     * <p>This is how an order becomes SHIPPED. {@code PUT /{id}/status} refuses the derived states, because a
     * header that can be set independently of its parcels is a header that can lie about them.
     *
     * <p>Shop-floor work, so it carries no admin gate — the same reasoning as PACKED in
     * {@link #updateStatus}: requiring an admin to dispatch a box would put the gate where the risk is not.
     */
    @PostMapping("/{id}/shipments")
    public ApiResponse<com.myplus.marketplace.dto.ShipmentDTO> ship(
            @PathVariable Long id,
            @RequestBody com.myplus.marketplace.dto.ShipmentDTO.Request body) {
        return ApiResponse.success(
                shipmentService.ship(id, body, CurrentUser.organizationId(), CurrentUser.userId()),
                "Shipment recorded");
    }

    /** Back-office process a return (E10, slice 71) — stock back (G2) + refund (card) → RETURNED. */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")   // a return issues a card refund — admin/owner only
    @PostMapping("/{id}/return")
    public ApiResponse<OrderDTO> processReturn(@PathVariable Long id) {
        return ApiResponse.success(
                orderService.processReturn(id, CurrentUser.organizationId(), CurrentUser.userId()), "Return processed");
    }
}
