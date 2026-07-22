package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Period close: one row per org. {@code lockedThrough} freezes everything dated on/before it (null = open). The GL
 * owns this (accounting close); business-service reads it to gate its user-facing ops.
 */
@Entity
@Table(name = "period_lock", uniqueConstraints = {
        @UniqueConstraint(name = "uq_period_lock_org", columnNames = "organization_id") })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PeriodLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "locked_through")
    private LocalDate lockedThrough;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
