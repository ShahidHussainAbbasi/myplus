package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** MKT-1e — checkout, tracking and seller-order wire shapes. */
public final class MarketplaceOrderDTOs {

    private MarketplaceOrderDTOs() {
    }

    /**
     * POST /public/mkt/checkout. Only {@code expectedPrice} is money, and it is never charged: it lets the server
     * tell the shopper the price moved instead of silently charging the new one. Any other field a client sends
     * (unitPrice, total, sellerOrganizationId …) is not bound.
     */
    public record CheckoutRequest(Long offerId, Integer quantity, BigDecimal expectedPrice, String customerName,
            String customerPhone, String address, String city, String idempotencyKey, String paymentMode, String cardToken) {

        /** MKT-1e: cash on delivery (every caller before MKT-1e2). */
        public CheckoutRequest(Long offerId, Integer quantity, BigDecimal expectedPrice, String customerName,
                String customerPhone, String address, String city, String idempotencyKey) {
            this(offerId, quantity, expectedPrice, customerName, customerPhone, address, city, idempotencyKey, null, null);
        }
    }

    /** MKT-1e2 — one payment fact as the shopper sees it. */
    public record PaymentView(String kind, String status, BigDecimal amount, String reason, LocalDateTime at) {
    }

    /** MKT-1e2 — one order in "My orders": what tracking shows, plus its payments and whether it can still be cancelled. */
    public record AccountOrderView(String orderNo, String status, String paymentMode, String paymentStatus, BigDecimal total,
            String cancelReason, LocalDateTime createdAt, String sellerName, String sellerOrderStatus, Long secondsToAccept,
            String city, List<LineView> lines, List<PaymentView> payments, boolean canCancel) {
    }

    /** What the SHOPPER sees of an order line: the snapshot of price, parties, warranty and returns. No commission. */
    public record LineView(Long offerId, Long mktProductId, String productName, Integer quantity, BigDecimal unitPrice,
            BigDecimal lineTotal, String stockSourceType, Long sellerOrganizationId, Long stockOwnerOrganizationId,
            Long custodianOrganizationId, Long fulfillerOrganizationId, Integer promiseHours, String warrantyProvider,
            Integer warrantyMonths, String warrantyStarts, String warrantyCovers, String warrantyExcludes,
            Integer returnDays) {
    }

    /** Checkout answer and tracking view. {@code secondsToAccept} is computed on the server (no clock skew). */
    public record OrderView(String orderNo, String status, String paymentMode, String paymentStatus,
            BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, String cancelReason, LocalDateTime createdAt,
            Long sellerOrderId, Integer sellerOrderVersion, String sellerOrderStatus, String sellerName,
            Long secondsToAccept, String city, List<LineView> lines) {
    }

    /** The seller's line: the shopper's view plus the commission terms the seller is charged (snapshot). */
    public record SellerLineView(Long lineId, LineView line, Long sourceProductId, Long commissionPolicyId,
            String commissionBasis, BigDecimal commissionRate, BigDecimal commissionFixed, String settlementStatus) {
    }

    /** One row of the seller's "Incoming orders". The seller delivers (COD, R-MKT-2), so it sees the contact. */
    public record SellerOrderView(Long id, Integer version, String orderNo, String acceptanceStatus,
            LocalDateTime acceptBy, Long secondsLeft, String customerName, String customerPhone, String address,
            String city, BigDecimal total, String invoiceNo, String storeOrderNo, String rejectReason,
            LocalDateTime createdAt, List<SellerLineView> lines) {
    }

    /** POST /mkt/seller-orders/{id}/accept. {@code serials}: order line id → the serial numbers / IMEIs sent. */
    public record AcceptRequest(Integer version, Map<Long, List<String>> serials) {
    }

    /** POST /mkt/seller-orders/{id}/reject. */
    public record RejectRequest(Integer version, String reason) {
    }

    /** GET/POST /mkt/operator/settings/accept-window. */
    public record AcceptWindow(Integer minutes) {
    }
}
