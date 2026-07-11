package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Audit #5: a GL event claimed for posting, keyed by (org, event_key). The unique index makes {@code postEvent}
 * idempotent — a duplicate outbox delivery finds the claim and is skipped (no second journal).
 */
@Entity
@Table(name = "gl_processed_event", uniqueConstraints = {
        @UniqueConstraint(name = "uq_gl_event_org_key", columnNames = {"organization_id", "event_key"}) })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "event_key", nullable = false, length = 64)
    private String eventKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
