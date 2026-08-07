package com.myplus.marketplace.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An order as the back-office sees it (E1, slice 46). */
@Data
public class OrderDTO {
    private Long id;
    private Long organizationId;        // input for a public (guest) order — no JWT identity
    private String invoiceNo;
    /** OMS O2: the merchant-facing order number, e.g. SO-000123 — what public tracking resolves. */
    private String orderNo;
    /** OMS O1: POSTED (produced an invoice) | LEGACY_UNPOSTED (pre-O1, never booked) | REVERSED (voided). */
    private String booksStatus;
    private String customerName;
    private String customerContact;
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
