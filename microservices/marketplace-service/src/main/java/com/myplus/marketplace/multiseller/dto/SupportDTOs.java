package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MKT-1f — support cases and returns on the wire.
 *
 * <p>What the CUSTOMER receives never names a seller's phone or carries an internal note: messages from anyone but the
 * customer are signed "MaxTheService support" (R8.2). The seller's task view carries the customer's phone and address —
 * the rider needs them for the pickup (R-MKT-13) — and only for that seller's own order.
 */
public final class SupportDTOs {

    private SupportDTOs() {
    }

    /** POST …/orders/{no}/cases. {@code topic}: ORDER_PROBLEM | RETURN | WARRANTY | OTHER; a RETURN names the line. */
    public record OpenCaseRequest(String topic, String note, Long lineId, Integer quantity, String reason) {
    }

    public record MessageRequest(String body) {
    }

    /** Operator: {@code internal} keeps the note off the customer's view. */
    public record ReplyRequest(String caseNo, String body, Boolean internal) {
    }

    public record TaskRequest(String caseNo, String note) {
    }

    public record ResolveRequest(String caseNo, String note) {
    }

    /** {@code decision}: APPROVED | REJECTED. */
    public record DecisionRequest(String returnNo, String decision, String note) {
    }

    /** Seller: the item is back. {@code outcome}: RESTOCK | QUARANTINE | WRITE_OFF. Cash on delivery needs {@code cashHandedBack}. */
    public record ReceivedRequest(String returnNo, String outcome, Boolean cashHandedBack) {
    }

    public record TaskReplyRequest(String caseNo, String body) {
    }

    public record FeeRequest(BigDecimal amount) {
    }

    /** One message, as its reader may see it: {@code from} is "You", "MaxTheService support" or (operator/seller) the role. */
    public record MessageView(String from, String body, boolean internal, LocalDateTime at) {
    }

    public record ReturnView(String returnNo, String status, String reason, Integer quantity, String productName,
            String bearerRole, String bearerOrgName, BigDecimal lineAmount, BigDecimal deduction, BigDecimal refundAmount,
            String refundChannel, String outcome, String creditNoteNo, String decisionNote, LocalDateTime createdAt) {
    }

    /** {@code sellerName}: MKT-2a — the seller whose part the case is about (an order from several sellers has one case each). */
    public record CaseView(String caseNo, String orderNo, String topic, String status, boolean urgent,
            LocalDateTime createdAt, List<MessageView> messages, List<ReturnView> returns, String sellerName) {
    }

    /** A row of the operator's queue. */
    public record CaseRow(String caseNo, String orderNo, String topic, String status, boolean urgent, String sellerName,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** A seller's task: the case's order, the customer's pickup details, what MaxTheService asked, and its returns. */
    public record SellerTask(String caseNo, String orderNo, String topic, String status, String customerName,
            String customerPhone, String address, String city, List<MessageView> messages, List<ReturnView> returns,
            LocalDateTime createdAt, String paymentMode) {
    }
}
