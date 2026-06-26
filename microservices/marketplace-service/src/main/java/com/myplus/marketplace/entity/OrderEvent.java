package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A notification event in an order's fulfilment timeline (slice 57): one row per status transition, recording what
 * was sent and to whom. The seam for real email/SMS delivery (logged for now).
 */
@Entity
@Table(name = "order_events", indexes = @Index(name = "idx_order_event_order", columnList = "order_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    private String status;        // fulfilment status this event announces
    private String channel;       // EMAIL | LOG (where the notification went)
    private String recipient;     // the customer contact notified
    private String note;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
