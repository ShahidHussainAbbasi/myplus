package com.myplus.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout (slice 69, E5). {@link Quote} is the server-computed totals breakdown shown before the shopper commits;
 * {@link Request} is the place-order body. Items + all money are server-authoritative (sourced from the cart) — the
 * client only chooses a shipping method and supplies contact/address.
 */
public class CheckoutDTO {

    /** Live totals for the current cart + chosen shipping method (no order is placed). */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Quote {
        private List<Line> items;
        private BigDecimal subtotal;
        private BigDecimal discount;        // coupon discount off the subtotal (slice 72)
        private BigDecimal taxTotal;
        private BigDecimal shippingFee;
        private BigDecimal total;
        private String shippingMethod;
        private String couponCode;          // applied code, or null
        private String couponMessage;       // why a supplied code was not applied (null when fine)
        private boolean addressRequired;

        /**
         * OMS O5c — will part of this order have to wait?
         *
         * <p>Carried on the QUOTE because the shopper has to know BEFORE they commit. Accepting a backorder
         * silently is how a shop earns a complaint: the customer believes everything is coming and finds out
         * only when half of it arrives. Null/false when everything can be filled today, which is the ordinary
         * case and changes nothing.
         */
        private boolean hasBackorder;
        /** When the outstanding part is promised. Null unless {@link #hasBackorder}. */
        private java.time.LocalDate promisedDate;
        /**
         * OMS O3 — does this store accept cash on delivery? Carried on the QUOTE because the storefront is
         * anonymous and has no other way to learn the store's policy: without it the shopper is offered COD
         * (the pre-selected tab) at a card-only store and is refused only after filling the whole form.
         * The server still enforces the same rule at place() — this field makes the UI honest, not safe.
         */
        private boolean codEnabled;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Line {
        private Long productId;
        /** OMS O5c — how many of THIS line will have to wait. 0 when it can be filled today. */
        private Integer backorderedQuantity;
        private String name;
        private BigDecimal unitPrice;
        private Integer quantity;
        private BigDecimal lineTotal;     // unitPrice × qty (ex-tax)
        private BigDecimal lineTax;       // EXCLUSIVE tax for the line
    }

    /** Place-order request. Money is NOT accepted from the client — only the cart token + choices. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Request {
        private Long organizationId;
        private String cartToken;
        private String customerToken;     // links the order to a signed-in shopper (slice 61)
        private String customerName;
        private String customerContact;
        private String shippingAddress;
        private String shippingMethod;    // PICKUP | STANDARD | EXPRESS
        private String couponCode;        // optional promo code (slice 72)
        private String paymentMode;       // COD | CARD
        private String cardToken;         // sandbox card token
    }
}
