package com.myplus.marketplace.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An order as the back-office sees it (E1, slice 46). */
@Data
public class OrderDTO {

    /**
     * O7 D1b — what the sale path forecasts for this order, returned by {@code PUT /orders/{id}}.
     *
     * <p><b>Outbound only, and advisory.</b> Populated on an amendment so the reviewer learns the amendment
     * loses money or breaks the outlet's credit limit AT THE MOMENT THEY DECIDE, rather than when the van is
     * loading. Empty when the amendment is within policy — and also empty when business-service could not be
     * reached, because a missing forecast must never fail an amendment that is already saved.
     *
     * <p>Dispatch remains authoritative: prices move, other orders consume the same credit. A screen showing
     * these must present them as a forecast, or a reviewer who trusts them stops reading the real failure.
     */
    private java.util.List<String> policyWarnings;

    private Long id;
    private Long organizationId;        // input for a public (guest) order — no JWT identity
    private String invoiceNo;
    /** OMS O2: the merchant-facing order number, e.g. SO-000123 — what public tracking resolves. */
    private String orderNo;
    /** OMS O1: POSTED (produced an invoice) | LEGACY_UNPOSTED (pre-O1, never booked) | REVERSED (voided). */
    private String booksStatus;
    private String customerName;
    private String customerContact;
    /**
     * OMS O7 D2c — the trade account this order is for (business-service customer id).
     *
     * <p>Sent by the booking screen from the outlet the rep picked, and carried to the invoice at dispatch so
     * the sale bills THAT account rather than a name-matched duplicate. Null for storefront/POS orders, where
     * resolving the buyer from name + contact is the correct behaviour for the channel.
     */
    private Long customerId;
    private BigDecimal total;
    private String fulfilmentStatus;
    private String source;              // POS | STOREFRONT
    private String paymentMode;         // COD | CARD
    private String paymentStatus;       // PENDING | PAID | FAILED (out)
    private String paymentRef;          // charge id (out)
    private String refundRef;           // refund id (out, slice 70)
    private BigDecimal refundedAmount;  // cumulative refunded (out, slice 70)
    private String cardToken;           // sandbox card token (in) — "fail" declines
    private String customerToken;       // storefront account session token (in) — links the order to the shopper (slice 61)
    private String cartToken;           // persistent cart handle (in) — closed on successful checkout (slice 68)
    private String reservationId;       // inventory saga hold (out, slice 49)
    private String reservationStatus;   // PENDING | CONFIRMED (out, slice 52)
    private String shippingAddress;
    private BigDecimal subTotal;        // checkout breakdown (slice 69) — total = subTotal + taxTotal + shippingFee
    private BigDecimal taxTotal;
    private BigDecimal shippingFee;
    private String shippingMethod;      // PICKUP | STANDARD | EXPRESS
    private String couponCode;          // applied promo code (slice 72)
    private BigDecimal discountAmount;  // coupon discount (slice 72)
    private String returnReason;        // RMA reason (slice 71)

    // ── OMS O7 D1 — distribution pre-sales ────────────────────────────────────────────────────────────────

    /**
     * Why the warehouse admin rejected this order (out). The booker cannot fix an order without it, which is
     * why {@code reject} refuses to proceed without one.
     */
    private String rejectionReason;

    /** OMS O7 D2 — the rep who booked it (out). Null for POS and storefront orders, which nobody books. */
    private Long bookedByUserId;
    /** The rep's name as stamped at booking — not resolved at read, so it survives them leaving. */
    private String bookedByName;

    /**
     * Why this amendment was made (in) — recorded on the amendment trail, never stored on the order itself.
     *
     * <p>On the DTO rather than a separate parameter because an amendment arrives as one document: the reviewer
     * changes three things and gives one reason for the lot, and splitting the reason out would invite callers
     * to omit it.
     */
    private String amendmentReason;

    /**
     * Client-supplied idempotency key for a booking (in).
     *
     * <p>A booker works on a phone at a shop counter with poor signal and WILL retry. Two orders from one
     * visit is a worse failure than one that appears not to have sent — the shop gets double the goods and the
     * booker is blamed. Same key discipline as the storefront checkout (OMS-3).
     */
    private String idempotencyKey;
    private List<Line> items;           // storefront cart lines — drive the stock reservation (slice 49)
    private LocalDateTime createdAt;

    // ── OMS O4 — what the back office needs to act ────────────────────────────────────────────────────────

    /**
     * Where this order may go next, from {@code FulfilmentStatus.allowedTransitions()}.
     *
     * <p>The screen renders one button per entry and nothing else. Before O4 the browser kept its own copy of
     * the lifecycle rules, which had already drifted from the server's: Cancel was offered on a SHIPPED order
     * (refused with 409) and RETURN_REQUESTED offered nothing at all. Publishing the whitelist is what removes
     * the second source of truth — the server still enforces it, the client simply stops guessing.
     *
     * <p>Empty for a terminal order, never null.
     */
    private List<String> allowedTransitions;

    /** OMS O4 — the order_events trail, oldest first. Written since slice 46; never shown to the merchant until now. */
    private List<Event> timeline;

    /** OMS O5b — the parcels this order has gone out in. Empty until something ships. */
    private List<ShipmentDTO> shipments;

    /**
     * OMS O5c — when the merchant expects to complete a backordered order. Null when nothing was owed, so an
     * order filled in full is never ageable and the "late" view stays meaningful.
     */
    private java.time.LocalDate promisedDate;

    /** OMS O5c — derived, never stored: a stored flag is wrong the moment the clock moves. */
    private Boolean late;

    /**
     * OMS O5c — is there now enough stock to fill what this order is still owed?
     *
     * <p>Derived on read for the same reason {@link #late} is: a stored "ready" flag starts going stale the
     * instant stock moves, and would need a job to keep true. Only populated by the outstanding-backorders read.
     */
    private Boolean readyToFulfil;

    /**
     * OMS O4 — how much of this order can still be refunded ({@code total - refundedAmount}, floored at zero).
     *
     * <p>Computed server-side because the refund dialog defaults to it, and a browser that derived it would be
     * the second place the rule lives — the first being {@code OrderService.refund}, which rejects an
     * over-refund. Two derivations of one number is how a UI comes to offer an amount the server refuses.
     */
    private BigDecimal refundableAmount;

    /** A cart line from the storefront checkout. */
    @Data
    public static class Line {
        private Long id;                // OMS O5b: shipping is requested per ORDER LINE, so the line needs identity
        private Long productId;
        /** Snapshotted at write (V14) — what the line was SOLD AS. Null for pre-V14 rows. */
        private String productName;
        private Integer quantity;
        /** OMS O5b — how much of this line has physically gone out. The header status is derived from these. */
        private Integer quantityShipped;
        /**
         * OMS O5c — accepted but not invoiced, because it could not be filled.
         *
         * <p>On the way IN this is set by the backorder split, and {@code quantity} then means "invoice this
         * many now". On the way OUT {@code quantity} is the full ordered amount, so the shopper and the back
         * office both see what was asked for alongside what is owed.
         */
        private Integer quantityBackordered;
        private BigDecimal price;
        /**
         * The concession given on THIS line, as an amount off the line total.
         *
         * <p>Distinct from overwriting {@code price}: a discounted line still shows what the goods LIST at,
         * so the shopkeeper can see what they were given and the distributor can total what they gave away.
         * Null or zero on an undiscounted line.
         */
        private BigDecimal discount;
    }

    /** One entry in the order's history — the same rows the shopper's tracking page reads. */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Event {
        private String status;
        private String note;
        private LocalDateTime at;
    }
}
