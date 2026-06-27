package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-product aggregate stock state (slice 33, Phase 5b) — the quantity/threshold/valuation fields that
 * used to live on inventory's Product. Product master now lives in catalog-service; inventory references it
 * by {@code productId}. One row per (organization, product).
 */
@Entity
@Table(name = "stock_levels",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "product_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockLevel {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Builder.Default
    private Float currentStock = 0f;

    private Float minStockLevel;
    private Float maxStockLevel;
    private Float reorderPoint;
    private BigDecimal costPrice;

    // Tenant scope.
    private Long organizationId;
    private Long userId;
    private String userType;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
