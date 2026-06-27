package com.myplus.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Storefront cart view (slice 68, E3). Returned by every cart op so the browser re-renders from the authoritative
 * server state. {@code cartToken} is the handle the browser persists in localStorage.
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CartDTO {

    private String cartToken;
    private boolean customerLinked;     // true once the cart belongs to a logged-in shopper
    private List<Line> items;
    private BigDecimal subtotal;        // sum of line totals (no tax/shipping — that's checkout, E5)
    private int count;                  // total quantity across lines

    /** Inbound request body for add/update/remove (org + token identify the cart). */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Request {
        private Long organizationId;
        private String cartToken;       // null on the first add → server mints one
        private String customerToken;   // storefront account session token → links/merges the cart (slice 61)
        private Long productId;
        private Integer quantity;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Line {
        private Long productId;
        private String name;
        private BigDecimal unitPrice;
        private Integer quantity;
        private BigDecimal lineTotal;
    }
}
