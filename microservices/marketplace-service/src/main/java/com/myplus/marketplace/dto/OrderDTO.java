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

    /** A cart line from the storefront checkout. */
    @Data
    public static class Line {
        private Long productId;
        private Integer quantity;
        private BigDecimal price;
    }
}
