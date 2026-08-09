package com.myplus.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One send REQUEST — what a module asked to have delivered, to however many people.
 *
 * <p>Slice 105 (D3). Paired with {@link NotificationDelivery}, one row per recipient: this record says
 * what was asked for, those say what actually happened to each person.
 */
@Entity
@Table(name = "notification_broadcast", uniqueConstraints = {
        // Idempotency. Both this service and its callers retry, so a re-POST after a timeout is a
        // certainty rather than a risk — without this key it would send the whole broadcast twice.
        @UniqueConstraint(name = "uk_broadcast_dedupe", columnNames = { "dedupe_key" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "broadcast_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    /**
     * Which module asked, in its own words — "EDU-NOTICE", "ALERT", "COVER".
     *
     * <p>Opaque to this service on purpose: it delivers, and the caller knows why. The same boundary
     * decision D-9 forced on the scheduling core, applied here before this one grows a reason to care.
     */
    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    @Builder.Default
    private Channel channel = Channel.EMAIL;

    /** Caller-supplied idempotency key. Null means "no dedupe" — MySQL allows many NULLs in the key. */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(name = "total_recipients", nullable = false)
    @Builder.Default
    private Integer totalRecipients = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
