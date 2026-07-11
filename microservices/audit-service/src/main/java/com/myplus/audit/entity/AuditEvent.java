package com.myplus.audit.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * One append-only audit entry: who ({@code userId}) did what ({@code action}) to which document
 * ({@code entityType}/{@code entityRef}) for how much ({@code amount}), from which service, and when. Immutable —
 * inserted once and never updated or deleted. {@code eventKey} makes ingestion idempotent per tenant.
 */
@Entity
@Table(name = "audit_event", uniqueConstraints = {
        @UniqueConstraint(name = "uq_audit_org_eventkey", columnNames = {"organization_id", "event_key"}) })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "source_service", length = 32)
    private String sourceService;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "entity_type", length = 32)
    private String entityType;

    @Column(name = "entity_ref", length = 64)
    private String entityRef;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;
}
