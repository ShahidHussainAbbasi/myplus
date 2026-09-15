package com.myplus.inventory.entity;

import java.math.BigDecimal;
import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One manual stock correction — WHAT changed on the shelf, by WHOM, in WHICH shop, and WHY.
 *
 * <p>BLK-5 (2026-09-15) added the shop and the key. Before it this table had no organization_id at all (V2's tenancy
 * pass never reached it) and all 34 live rows had a NULL adjusted_by, because nothing sent one — so a record described
 * as "audited (reason/who/when)" named neither the person nor the tenant.
 * Design: microservices/docs/slices/blk-5-stock-adjust-guard.md
 */
@Entity
@Table(name = "stock_adjustments",
        uniqueConstraints = @UniqueConstraint(name = "uq_adj_org_idem",
                columnNames = {"organization_id", "idempotency_key"}))
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

    /** WHO — stamped from the authenticated caller by the server. Never taken from the request body. */
    private Long adjustedBy;

    @Builder.Default
    private LocalDateTime adjustedAt = LocalDateTime.now();

    @Column(length = 1000)
    private String notes;

    /** BLK-5 — WHICH SHOP (V11). Stamped from the caller; the scoped reads and the unique key are per tenant. */
    @Column(name = "organization_id")
    private Long organizationId;

    /**
     * BLK-5 — the caller's key for ONE intended correction (V11).
     *
     * <p>UNIQUE per tenant ({@code uq_adj_org_idem}), so a retry, a double press or a racer carrying the same key
     * records ONE adjustment and moves the stock ONCE. NULL means no de-duplication (an older client); MySQL treats
     * NULLs as distinct, so the constraint never refuses those. 191 = the utf8mb4 VARCHAR an index can hold whole.
     */
    @Column(name = "idempotency_key", length = 191)
    private String idempotencyKey;
}
