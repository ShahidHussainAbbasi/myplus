package com.myplus.campaign.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Long templateId;

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private Long createdBy;

    @Builder.Default
    private int totalRecipients = 0;
    @Builder.Default
    private int sent = 0;
    @Builder.Default
    private int delivered = 0;
    @Builder.Default
    private int opened = 0;
    @Builder.Default
    private int clicked = 0;
    @Builder.Default
    private int bounced = 0;
    @Builder.Default
    private int unsubscribed = 0;

    private BigDecimal budget;
    private BigDecimal spent;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (status == null) status = Status.DRAFT;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Type { EMAIL, SMS, PUSH, SOCIAL }
    public enum Status { DRAFT, SCHEDULED, RUNNING, PAUSED, COMPLETED, CANCELLED }
}
