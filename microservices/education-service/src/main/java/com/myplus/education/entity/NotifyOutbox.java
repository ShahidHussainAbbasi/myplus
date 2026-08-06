package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Slice N1 — one pending notification, captured in the SAME transaction as the decision it announces.
 *
 * The third use of the transactional-outbox pattern in this service, after {@code GlOutbox} (0.1) and
 * {@code AuditOutbox} (1.3). Capture is atomic with the business write; delivery is AFTER_COMMIT and
 * retried by the shared {@code OutboxRelay}.
 *
 * <p>Assigning cover must never fail because notification-service is unreachable — but the message must
 * never be silently lost either, which is exactly what 2.2's logging stub did. An outbox is the only shape
 * that gives both.
 *
 * <p>Holds the RESOLVED recipient address and the RENDERED text (design D3), not a staff id: the relay runs
 * on a schedule with no request context, and the platform snapshots values at the moment of a decision so a
 * later edit cannot restate what was sent.
 *
 * <p>Consequence worth stating: {@code SENT} means notification-service accepted the message, not that a
 * human received it. There is no delivery receipt on this side — that is slice 105's half.
 */
@Entity
@Table(name = "notify_outbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotifyOutbox implements com.myplus.common.outbox.OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. COVER_ASSIGNED. Free text, not an enum column — see the migration header. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", length = 2000)
    private String body;

    /** One per event, so a retried delivery is recognisable downstream. */
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
}
