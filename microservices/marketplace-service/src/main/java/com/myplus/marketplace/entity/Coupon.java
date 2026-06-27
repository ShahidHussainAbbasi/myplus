package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A store promo code (slice 72, E13). Org-scoped, unique code per store. PERCENT (value = 0–100) or FIXED (value =
 * amount off the subtotal). Optional min-spend, validity window, and usage cap.
 */
@Entity
@Table(name = "coupon",
        uniqueConstraints = @UniqueConstraint(name = "uq_coupon_org_code", columnNames = {"organization_id", "code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Coupon {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    private String code;            // stored upper-cased

    private String type;            // PERCENT | FIXED

    @Column(precision = 19, scale = 2)
    private BigDecimal value;

    @Column(name = "min_spend", precision = 19, scale = 2)
    private BigDecimal minSpend;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Builder.Default
    @Column(name = "used_count")
    private Integer usedCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
