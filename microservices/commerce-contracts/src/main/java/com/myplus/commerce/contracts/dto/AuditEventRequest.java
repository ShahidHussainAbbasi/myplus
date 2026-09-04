package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.*;

/**
 * A money/stock/config event a producer emits to the standalone audit-service (via {@code AuditClient}). Identity
 * (org + actor) is derived by audit-service from the authenticated request, not this payload. {@code eventKey} makes
 * delivery idempotent so an outbox retry is a no-op.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditEventRequest {
    private String sourceService;   // business | finance | inventory | ...
    private String action;          // SALE | VOID_SALE | RECEIPT | PAYMENT | ...
    private String entityType;      // INVOICE | BILL | CUSTOMER | VENDOR
    private String entityRef;       // invoiceNo / purchaseInvoiceNo / voucher
    private BigDecimal amount;
    private String details;
    private String eventKey;        // producer-generated UUID (dedup)
    private LocalDateTime occurredAt;

    // ── E4 — the control-plane fields ─────────────────────────────────────────────────────────────
    // Identity (which tenant the row belongs to, which user acted) still comes from the authenticated
    // request. These DESCRIBE the actor; they cannot re-file the record against a different tenant.
    private Long actorOrgId;        // the actor's own org — equal to the subject org for an insider
    private String actorType;       // MEMBER | PLATFORM_OPERATOR | SYSTEM (AuditActorType)
    private String actorEmail;      // stamped, so the trail survives the person leaving
    private String reason;          // mandatory on every control-plane write since E2
    private String beforeValue;     // a record of a change that keeps only the new value shows no change
    private String afterValue;
}
