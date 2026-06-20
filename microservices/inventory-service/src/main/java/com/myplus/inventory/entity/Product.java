package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products", indexes = {@Index(columnList = "sku", unique = true)})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    private String unit;
    private Float minStockLevel;
    private Float maxStockLevel;
    private Float reorderPoint;

    @Builder.Default
    private Float currentStock = 0f;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal taxRate;

    @Builder.Default
    private Boolean isActive = true;

    private String imageUrl;
    private Long createdBy;

    // Tenant scope (slice 33, Phase 4.5): organization_id from the gateway X-Org-Id; user_id/user_type
    // as audit and for the NULL-fallback read of pre-migration rows. All nullable (ddl-auto adds them).
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
