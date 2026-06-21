package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Transition record mapping a business {@code Item} to the catalog {@code Product} it became (slice 33, U2).
 * Lets the sell flow translate itemId→productId during the strangler window; dropped at cutover (U5).
 */
@Entity
@Table(name = "item_catalog_map",
        uniqueConstraints = @UniqueConstraint(name = "uq_icm_org_item", columnNames = {"organization_id", "item_id"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ItemCatalogMap {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    private Long organizationId;

    /** Whether this item's local Stock has been seeded into inventory yet (slice 33, U2b). */
    @Builder.Default
    private boolean stockMigrated = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}
