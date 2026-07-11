package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit #6: a queued audit event (transactional outbox). Written in the SAME tx as the money/stock change so the
 * event can't be lost; delivered to audit-service by AuditService (AFTER_COMMIT + @Scheduled relay). Mirrors GlOutbox.
 */
@Data
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox implements com.myplus.business_service.service.outbox.OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "entity_type", length = 32)
    private String entityType;
    @Column(name = "entity_ref", length = 64)
    private String entityRef;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 500)
    private String details;

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

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
