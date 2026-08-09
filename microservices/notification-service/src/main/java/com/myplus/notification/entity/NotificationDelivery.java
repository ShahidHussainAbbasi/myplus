package com.myplus.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * What happened to ONE recipient — slice 105's whole reason for existing.
 *
 * <p>Before this, a failed alert to 300 guardians was a log line: no per-recipient record, no retry, and
 * nothing to show the school. A parent asking why they never received the closure notice could not be
 * answered at all.
 *
 * <p><b>{@link DeliveryStatus#SENT} means the mail server accepted it, not that it reached an inbox.</b>
 * Stated here because the distinction matters to whoever reads this table in a dispute: bounces and spam
 * filtering happen after this point and are invisible without provider webhooks, which this slice does
 * not build.
 */
@Entity
@Table(name = "notification_delivery")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "broadcast_id", nullable = false)
    private Long broadcastId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    @Builder.Default
    private Channel channel = Channel.EMAIL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    /** Why the last attempt failed. Truncated on write — a stack trace must not break the insert. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
