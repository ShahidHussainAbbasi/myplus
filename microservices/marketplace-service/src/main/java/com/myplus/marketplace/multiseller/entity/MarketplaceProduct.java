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
 * MKT-1b — a canonical marketplace product (V25): what the customer sees, once, however many sellers offer it.
 *
 * <h3>No organization_id, deliberately</h3>
 * A canonical product belongs to no tenant. It is created and corrected only by the platform operator; sellers
 * reach it only through their own tenant-scoped {@link MarketplaceProductSource} rows. Source §5: a merchant's raw
 * POS product is never published directly.
 */
@Entity
@Table(name = "mkt_product")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UNIQUE: the same composite key (or GTIN) can never be two canonical products. */
    @Column(name = "identity_key", nullable = false, length = 400)
    private String identityKey;

    @Column(name = "canonical_name", nullable = false, length = 255)
    private String canonicalName;

    @Column(name = "brand", nullable = false, length = 80)
    private String brand;

    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Column(name = "variant", length = 80)
    private String variant;

    @Column(name = "colour", length = 60)
    private String colour;

    @Column(name = "size", length = 60)
    private String size;

    @Column(name = "unit", length = 40)
    private String unit;

    @Column(name = "pack_size", length = 40)
    private String packSize;

    @Column(name = "condition_grade", length = 40)
    private String conditionGrade;

    @Column(name = "warranty_type", length = 60)
    private String warrantyType;

    @Column(name = "gtin", length = 14)
    private String gtin;

    @Column(name = "category_name", length = 120)
    private String categoryName;

    /** {@code MarketplaceStatus.Regulated} name: NONE | RESTRICTED | PRESCRIPTION. */
    @Column(name = "regulated_status", nullable = false, length = 16)
    private String regulatedStatus;

    /** {@code MarketplaceStatus.Approval} name. Operator-created products start APPROVED. */
    @Column(name = "approval_status", nullable = false, length = 16)
    private String approvalStatus;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

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
