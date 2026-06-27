package com.myplus.catalog.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Product master (slice 33, Phase 5) — descriptive attributes only. Quantity/threshold/valuation state
 * (currentStock, min/max level, reorderPoint, costPrice) lives in inventory-service's StockLevel.
 */
@Entity
@Table(name = "products", indexes = {@Index(columnList = "sku")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    private String unit;

    /** Brand/manufacturer (slice 33, U1 — parity with business Item.company for the item→product migration). */
    private String manufacturer;

    private BigDecimal sellingPrice;
    private BigDecimal taxRate;

    @Builder.Default
    private Boolean isActive = true;

    private String imageUrl;
    private Long createdBy;

    // Tenant scope (carried from inventory's Phase 4.5 scoping) — nullable; ddl-auto creates them.
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
