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
 * MKT-1b — one seller's proposal that a product in ITS catalogue is a given marketplace product (V25).
 *
 * <p>Tenant-scoped by {@link #organizationId} (the seller, from the JWT). The identity attributes are what the
 * seller DECLARED; {@link #sourceRegulated} and {@link #sourceProductName} were read from catalog-service at
 * proposal time and are never taken from the request. Source §6: a proposal is never merged without an operator.
 */
@Entity
@Table(name = "mkt_product_source")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceProductSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "source_product_id", nullable = false)
    private Long sourceProductId;

    @Column(name = "source_product_name", length = 255)
    private String sourceProductName;

    @Column(name = "proposed_identity_key", nullable = false, length = 400)
    private String proposedIdentityKey;

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

    @Column(name = "source_regulated", nullable = false, length = 16)
    private String sourceRegulated;

    /** {@code MarketplaceStatus.Match} name; every move goes through {@code MarketplaceStateMachines.MATCH}. */
    @Column(name = "match_status", nullable = false, length = 20)
    private String matchStatus;

    @Column(name = "suggested_product_id")
    private Long suggestedProductId;

    @Column(name = "mkt_product_id")
    private Long mktProductId;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "proposed_by_user_id")
    private Long proposedByUserId;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

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
