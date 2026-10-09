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
            String customerPhone, String address, String city, String idempotencyKey, String paymentMode, String cardToken,
            List<CheckoutLine> lines) {

        /** MKT-1e2: one offer, cash or card (every caller before MKT-2a). */
        public CheckoutRequest(Long offerId, Integer quantity, BigDecimal expectedPrice, String customerName,
                String customerPhone, String address, String city, String idempotencyKey, String paymentMode, String cardToken) {
            this(offerId, quantity, expectedPrice, customerName, customerPhone, address, city, idempotencyKey, paymentMode,
                    cardToken, null);
        }

        /** MKT-1e: cash on delivery (every caller before MKT-1e2). */
        public CheckoutRequest(Long offerId, Integer quantity, BigDecimal expectedPrice, String customerName,
                String customerPhone, String address, String city, String idempotencyKey) {
            this(offerId, quantity, expectedPrice, customerName, customerPhone, address, city, idempotencyKey, null, null);
        }
    }

    /**
     * MKT-2a — one basket line. When {@code lines} is sent, {@code offerId}/{@code quantity}/{@code expectedPrice} are
     * ignored. {@code expectedPrice} is the unit price the shopper saw; it is never charged.
     */
    public record CheckoutLine(Long offerId, Integer quantity, BigDecimal expectedPrice) {
    }

    /**
     * MKT-2a — one seller's part of an order, as the shopper sees it: its own status, deadline, total and lines.
     * {@code promisedBy} is when the seller's longest delivery promise runs out, counted from the acceptance (or,
     * before it, from the order).
     */
    public record PartView(Long id, Integer version, Long sellerOrganizationId, String sellerName, String status,
            Long secondsToAccept, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, LocalDateTime promisedBy,
            LocalDateTime deliveredAt, List<LineView> lines, ShortageView shortage) {
    }

    /**
     * MKT-2b — what became of a part its seller could not fulfil, as the shopper sees it. {@code result}: PENDING while
     * another seller is looked for, then REASSIGNED ({@code movedTo} names the new seller), SUBSTITUTION_REQUESTED (the
     * shopper is asked: the proposal fields and {@code secondsToDecide}), LINE_CANCELLED or ORDER_CANCELLED.
     * {@code priceDifference} is the proposal's total minus the part's (negative when cheaper).
     */
    public record ShortageView(Long id, String result, String movedTo, String proposalSeller, BigDecimal proposalTotal,
            Integer proposalPromiseHours, Long secondsToDecide, BigDecimal priceDifference, String decision) {
    }

    /** MKT-2b — POST /public/mkt/orders/{no}/shortages/{id}/decision: the shopper's answer, proven by the order's phone. */
    public record ShortageDecision(String phone, Boolean accept) {
    }

    /** MKT-2b — the record of a part a seller did not fulfil, as that seller sees it (R11.4), and its dispute (R12.4). */
    public record SellerShortageView(Long id, String cause, String responsibleRole, String result, String status,
            String disputeNote, String decisionNote, boolean canDispute) {
    }

    /** MKT-2b — POST /mkt/seller/shortages/{id}/dispute. */
    public record DisputeRequest(String note) {
    }

    /** MKT-2b — the operator's list of unfulfilled parts: cause, party, outcome, dispute, and how long it took. */
    public record OperatorShortageView(Long id, String orderNo, Long sellerOrganizationId, String sellerName, String cause,
            String responsibleRole, String evidence, String result, String status, String disputeNote, String decisionNote,
            String customerDecision, LocalDateTime createdAt, LocalDateTime resolvedAt, Long minutesToResolve) {
    }

    /** MKT-2b — POST /mkt/operator/shortages/{id}/decide: UPHELD (the record stands) or OVERTURNED. */
    public record ShortageRuling(String outcome, String note) {
    }

    /** MKT-1e2 — one payment fact as the shopper sees it. */
    public record PaymentView(String kind, String status, BigDecimal amount, String reason, LocalDateTime at) {
    }

    /** MKT-1e2 — one order in "My orders": what tracking shows, plus its payments and whether it can still be cancelled. */
    public record AccountOrderView(String orderNo, String status, String paymentMode, String paymentStatus, BigDecimal total,
            String cancelReason, LocalDateTime createdAt, String sellerName, String sellerOrderStatus, Long secondsToAccept,
            String city, List<LineView> lines, List<PaymentView> payments, boolean canCancel, LocalDateTime deliveredAt,
            boolean canGetHelp, List<PartView> sellerOrders) {
    }

    /** What the SHOPPER sees of an order line: the snapshot of price, parties, warranty and returns. No commission. */
    public record LineView(Long id, Long offerId, Long mktProductId, String productName, Integer quantity, BigDecimal unitPrice,
            BigDecimal lineTotal, String stockSourceType, Long sellerOrganizationId, Long stockOwnerOrganizationId,
            Long custodianOrganizationId, Long fulfillerOrganizationId, Integer promiseHours, String warrantyProvider,
            Integer warrantyMonths, String warrantyStarts, String warrantyCovers, String warrantyExcludes,
            Integer returnDays) {
    }

    /** Checkout answer and tracking view. {@code secondsToAccept} is computed on the server (no clock skew). */
    public record OrderView(String orderNo, String status, String paymentMode, String paymentStatus,
            BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, String cancelReason, LocalDateTime createdAt,
            Long sellerOrderId, Integer sellerOrderVersion, String sellerOrderStatus, String sellerName,
            Long secondsToAccept, String city, List<LineView> lines, List<PartView> sellerOrders) {
    }

    /** The seller's line: the shopper's view plus the commission terms the seller is charged (snapshot). */
    public record SellerLineView(Long lineId, LineView line, Long sourceProductId, Long commissionPolicyId,
            String commissionBasis, BigDecimal commissionRate, BigDecimal commissionFixed, String settlementStatus) {
    }

    /** One row of the seller's "Incoming orders". The seller delivers (COD, R-MKT-2), so it sees the contact. */
    public record SellerOrderView(Long id, Integer version, String orderNo, String acceptanceStatus,
            LocalDateTime acceptBy, Long secondsLeft, String customerName, String customerPhone, String address,
            String city, BigDecimal total, String invoiceNo, String storeOrderNo, String rejectReason,
            LocalDateTime createdAt, List<SellerLineView> lines, Long storeOrderId, String paymentMode, LocalDateTime deliveredAt,
            SellerShortageView shortage) {
    }

    /** POST /mkt/seller-orders/{id}/accept. {@code serials}: order line id → the serial numbers / IMEIs sent. */
    public record AcceptRequest(Integer version, Map<Long, List<String>> serials) {
    }

    /** POST /mkt/seller-orders/{id}/reject. */
    public record RejectRequest(Integer version, String reason, String cause) {

        public RejectRequest(Integer version, String reason) {
            this(version, reason, null);
        }
    }

    /**
     * GET/POST /mkt/operator/settings/accept-window. MKT-2a added {@code multiSeller} and MKT-2b {@code reroute} to the
     * same form; on a POST, a null field is left as it is.
     */
    /** MKT-2-06 — a part worth more than {@code above} rupees gets {@code minutes} to be accepted. */
    public record AcceptTier(java.math.BigDecimal above, Integer minutes) {
    }

    /** MKT-2-06 — the base window (every part above no rule) and the rules, by amount. */
    public record AcceptTiers(Integer baseMinutes, List<AcceptTier> tiers) {
    }

    public record AcceptWindow(Integer minutes, Boolean multiSeller, Boolean reroute) {

        public AcceptWindow(Integer minutes) {
            this(minutes, null, null);
        }

        public AcceptWindow(Integer minutes, Boolean multiSeller) {
            this(minutes, multiSeller, null);
        }
    }

    // ── MKT-2c: live routing, for the operator ──────────────────────────────────────────────────────────

    /** GET /mkt/operator/routing. {@code slowSeller*} and {@code sellers} only when the test switch is available. */
    public record RoutingView(long holdTimeoutMs, long deadlineMs, int breakerFailures, long breakerOpenSeconds,
            List<RoutingCircuit> notAnswering, boolean testSwitch, Long slowSellerOrganizationId, Long slowDelayMs,
            String slowSellerName, List<RoutingSeller> sellers) {}

    /** A seller not being asked until {@code until}, after {@code failures} calls in a row it did not answer. */
    public record RoutingCircuit(Long sellerOrganizationId, String sellerName, int failures, java.time.LocalDateTime until) {}

    public record RoutingSeller(Long sellerOrganizationId, String name) {}

    /** POST /mkt/operator/routing/test: seller null or delay 0 switches it off. */
    public record RoutingTest(Long sellerOrganizationId, Long delayMs) {}

    /** POST /mkt/operator/routing/close. */
    public record RoutingClose(Long sellerOrganizationId) {}
}
