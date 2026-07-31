package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Slice 0.1: a queued GL posting event (transactional outbox) for education.
 *
 * Written in the SAME transaction as the fee collection, so a committed fee can never lose its journal entry;
 * delivered to finance-service by {@code GlOutboxService} (AFTER_COMMIT + a {@code @Scheduled} relay). The
 * delivery state machine is the shared {@code common-outbox} {@link com.myplus.common.outbox.OutboxEntry} /
 * OutboxRelay — the same one business-service uses for its GL and audit outboxes.
 *
 * This table lives in myplusdb_education, NOT shared with business: the outbox pattern gives cross-service
 * atomicity precisely so services need no shared database.
 *
 * Columns mirror commerce-contracts {@code PostingEventRequest}. Only the subset a fee needs is populated —
 * subTotal/taxTotal/cost/storeCredit stay null (tuition has no tax line and a service has no COGS).
 */
@Data
@Entity
@Table(name = "gl_outbox", indexes = {
        @Index(name = "idx_edu_outbox_pending", columnList = "status,id"),
        @Index(name = "idx_edu_outbox_org", columnList = "organization_id")
})
public class GlOutbox implements com.myplus.common.outbox.OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;   // FEE_COLLECTION

    /** Stable per-event UUID so finance dedups a duplicate delivery — see ProcessedEvent(org, eventKey). */
    @Column(name = "event_key", length = 64)
    private String eventKey;

    /** Human-traceable reference: the fee record id (there is no invoice number in education yet). */
    private String ref;

    @Column(name = "grand_total", precision = 19, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "paid_amount", precision = 19, scale = 2)
    private BigDecimal paidAmount;

    /** CASH | CHEQUE — translated from FeeCollection.receivedIn (Cash|Check). */
    @Column(length = 30)
    private String method;

    @Column(nullable = false, length = 20)
    private String status;      // PENDING | POSTED | FAILED

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
