package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Product master lives in catalog-service (slice 33, Phase 5b) — referenced by id, not a JPA FK.
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Column(nullable = false)
    private Float quantity;

    /** Held by open reservations (slice 33, Phase 6a). Available = quantity - reservedQuantity. */
    @Builder.Default
    private Float reservedQuantity = 0f;

    private String batchNo;
    private String lotNo;
    private LocalDate expiryDate;
    private BigDecimal purchasePrice;

    /** P11 (slice 55): false = quarantined (e.g. a pharmacy return) — excluded from FEFO/availability so it is
     *  never re-sold/dispensed. null or true = sellable (back-compat for pre-P11 rows). */
    private Boolean restockable;

    @Builder.Default
    private LocalDateTime entryDate = LocalDateTime.now();

    private Long supplierId;

    @Column(length = 1000)
    private String notes;

    // Tenant scope (slice 33, Phase 4.5) — nullable; ddl-auto/Flyway V2 adds them.
    private Long organizationId;
    private Long userId;
    private String userType;
}
