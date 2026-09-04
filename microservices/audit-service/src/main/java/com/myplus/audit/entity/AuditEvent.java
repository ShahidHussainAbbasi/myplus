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

    /**
     * E4 — the ACTOR AXIS: which organization the actor belongs to, and whether that is this tenant.
     *
     * <p>Every row written before E4 had {@code actorOrgId == organizationId}, so the question never arose. A
     * platform operator acting on a customer's tenant is the case that needs it: without these two columns the
     * trail either hides the change from the customer or attributes it to one of their own staff, and the
     * second is worse — an owner auditing their configuration would blame a colleague for a platform decision.
     *
     * <p>{@code actorType} is {@code MEMBER · PLATFORM_OPERATOR · SYSTEM} and deliberately not a role name.
     * audit-service does not know roles; encoding one would make the column lie the moment that person's role
     * changed. Inside-or-outside is a fact about the event and does not decay.
     */
    @Column(name = "actor_org_id")
    private Long actorOrgId;

    @Column(name = "actor_type", length = 24)
    private String actorType;

    /** Stamped, not resolved on read — the trail must stay readable after the person's user row is gone. */
    @Column(name = "actor_email", length = 160)
    private String actorEmail;

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

    /**
     * E4 — WHY. Its own column rather than free text inside {@code details}: mandatory on every control-plane
     * write since E2, and the only question anybody asks of this trail six months later.
     */
    @Column(name = "reason", length = 255)
    private String reason;

    /** E4 — what it changed FROM and TO. A record keeping only the new value cannot show a change. */
    @Column(name = "before_value", length = 64)
    private String beforeValue;

    @Column(name = "after_value", length = 64)
    private String afterValue;

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;
}
