package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-0a — MaxTheService's decision about one seller (V24). One row per seller organization.
 *
 * <p>{@link #status} is a String over a VARCHAR column (the expense-service recipe), holding a
 * {@code MarketplaceStatus.SellerAccount} name. Every move goes through {@code MarketplaceStateMachines.SELLER_ACCOUNT}
 * in the service; nothing sets it directly from a request.
 */
@Entity
@Table(name = "mkt_seller_account")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceSellerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The SELLER's organization — from the JWT on every tenant path, never from a request body. */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "applied_by_user_id")
    private Long appliedByUserId;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Two operators deciding one seller at once: the second gets a 409, never a silent overwrite (common-web R2). */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
