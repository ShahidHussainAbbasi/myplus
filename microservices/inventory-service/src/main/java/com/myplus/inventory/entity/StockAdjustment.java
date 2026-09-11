package com.myplus.inventory.entity;

import java.math.BigDecimal;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_adjustments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockAdjustment {

    public enum AdjustmentType { INCREASE, DECREASE, TRANSFER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Product master lives in catalog-service (slice 33, Phase 5b) — referenced by id.
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentType adjustmentType;

    @Column(nullable = false)
    private BigDecimal quantity;

    private String reason;
    private Long adjustedBy;

    @Builder.Default
    private LocalDateTime adjustedAt = LocalDateTime.now();

    @Column(length = 1000)
    private String notes;

    /**
     * ⭐ PERF-9 — the product's on-hand AFTER this adjustment. Transient: not a column, not persisted.
     *
     * <p>An adjustment row records what CHANGED; this carries what the change RESULTED IN, so the caller
     * does not have to read the level back over a second round trip. Derived state on a write response,
     * never a second source of truth — the StockLevel remains the only record of on-hand.
     */
    @jakarta.persistence.Transient
    private Float resultingOnHand;
}
