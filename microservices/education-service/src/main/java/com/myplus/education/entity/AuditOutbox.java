package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Slice 1.3 (D5) — one pending audit event, captured in the SAME transaction as the marks write.
 *
 * Reuses the transactional-outbox pattern already proven twice on this platform: business-service's
 * {@code AuditOutbox} and education's own {@code GlOutbox} (slice 0.1). Capture is atomic with the
 * business write; delivery to audit-service is AFTER_COMMIT and retried by the shared
 * {@code OutboxRelay}. A marks save must never fail because an audit service is unreachable — but the
 * event must never be lost either, which is exactly what an outbox buys.
 *
 * Consequence worth stating (design §7): the audit log is complete EVENTUALLY, not instantly.
 */
@Entity
@Table(name = "audit_outbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditOutbox implements com.myplus.common.outbox.OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MARK_ENTERED · MARK_CHANGED · EXAM_LOCKED · EXAM_UNLOCKED. */
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    /** Which row: "paper=12,student=ENR-001" for a mark, the exam id for a status change. */
    @Column(name = "entity_ref")
    private String entityRef;

    /**
     * For MARK_CHANGED this carries the OLD and NEW values. An audit that records only the new number
     * cannot answer "was this altered?", which is the one question anyone actually asks of it.
     */
    @Column(name = "details", length = 1000)
    private String details;

    /** Idempotency key so a relay retry cannot record the same event twice. */
    @Column(name = "event_key")
    private String eventKey;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts")
    private Integer attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (status == null) status = "PENDING";
        if (attempts == null) attempts = 0;
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (occurredAt == null) occurredAt = LocalDateTime.now();
    }
}
