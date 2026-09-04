package com.myplus.common.audit;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import com.myplus.common.outbox.OutboxEntry;

import lombok.Data;

/**
 * E4 — the audit outbox row, declared once.
 *
 * <h3>A {@code @MappedSuperclass}, deliberately, and not an {@code @Entity}</h3>
 * Each service still owns its own {@code audit_outbox} table and its own Flyway migration — the microservice
 * schema-ownership standard, the same reason {@code org_setting} is per-service. What is shared is the COLUMN
 * SET and the delivery behaviour, not the table. A consumer writes:
 *
 * <pre>{@code
 * @Entity @Table(name = "audit_outbox")
 * public class AuditOutbox extends AbstractAuditOutbox {}
 * }</pre>
 *
 * <h3>Why the row carries the payload rather than a serialized blob</h3>
 * Because the outbox is also where an operator looks when delivery has failed. A dead-lettered row whose
 * contents are a JSON string is a row nobody reads, and the whole point of {@code FAILED} status is that a
 * human can see what did not make it.
 */
@Data
@MappedSuperclass
public abstract class AbstractAuditOutbox implements OutboxEntry {

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

    @Column(name = "details", length = 500)
    private String details;

    // ── E4: the control-plane fields ──────────────────────────────────────────────────────────────

    /**
     * WHY. Mandatory on every control-plane write since E2, and the only question anybody asks of the trail
     * six months later. Its own column rather than free text inside {@code details}, which is shared and
     * silently truncated at 500 characters.
     */
    @Column(name = "reason", length = 255)
    private String reason;

    /**
     * What the value was, and what it became. Both, always: a record that keeps only the new value cannot
     * show a change at all — a revocation and a re-revocation read identically.
     */
    @Column(name = "before_value", length = 64)
    private String beforeValue;

    @Column(name = "after_value", length = 64)
    private String afterValue;

    /** The actor's own organization. Equal to {@link #organizationId} for an insider; different for staff. */
    @Column(name = "actor_org_id")
    private Long actorOrgId;

    /** {@link AuditActorType}. Never a role name — see that enum for why. */
    @Column(name = "actor_type", length = 24)
    private String actorType;

    /** Stamped at write, so the trail stays readable after the person has left and their user row is gone. */
    @Column(name = "actor_email", length = 160)
    private String actorEmail;

    // ── delivery state (driven by the shared OutboxRelay) ─────────────────────────────────────────

    @Column(name = "event_key", length = 64)
    private String eventKey;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;      // PENDING | POSTED | FAILED

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * The tenant the event is ABOUT — not necessarily the tenant the actor belongs to.
     *
     * <p>This is the field E4 turned into a decision. The producer delivers with
     * {@code runAs(userId, organizationId)}, so whatever is stored here is the org audit-service files the
     * row under, and {@code audit_event} is append-only: a control-plane event written against the operator's
     * org is invisible to the customer forever.
     */
    @Column(name = "organization_id")
    private Long organizationId;

    /** The individual who acted. Read from the validated token, never from a request body. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
