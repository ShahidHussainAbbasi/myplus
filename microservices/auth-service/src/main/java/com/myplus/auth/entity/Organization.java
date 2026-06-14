package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A tenant — a school/college organization. Branches are organizations with a parentId.
 * The owner who created it is ownerUserId. Users join via {@link Membership} (many-to-many),
 * so a single user can belong to several organizations.
 */
@Entity
@Table(name = "organizations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** SCHOOL | COLLEGE | ... */
    private String type;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** Parent organization id for a branch; null for a top-level org. */
    @Column(name = "parent_id")
    private Long parentId;

    @Builder.Default
    private String status = "ACTIVE";

    /**
     * Billing/entitlement plan — the tenant-level source of truth for limits (slice 32).
     * TRIAL (self-signup default, time-boxed by {@link #trialEndsAt}) · FREE · PRO · DEMO (shared sandbox).
     * Legacy/auto-created orgs default to FREE so they are neither capped nor trial-expired.
     */
    @Builder.Default
    private String plan = "FREE";

    /** When a TRIAL ends (now + 14d at signup). Null for non-trial plans. Trial is time-boxed, not capped. */
    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    /** Per-module write cap; null = unlimited. DEMO sandbox tenants → 50; TRIAL/FREE/PRO → null. */
    @Column(name = "entry_cap")
    private Integer entryCap;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
