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
        private BigDecimal taxTotal;
        private BigDecimal shippingFee;
        private BigDecimal total;
        private String shippingMethod;
        private boolean addressRequired;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Line {
        private Long productId;
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
        private String paymentMode;       // COD | CARD
        private String cardToken;         // sandbox card token
    }
}
