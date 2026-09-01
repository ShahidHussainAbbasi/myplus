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
    /**
     * U0 — BASE UNITS, exact.
     *
     * <p>Was {@code Float}. Selling loose divides a pack, and a pack of 3, 6, 7 or 24 does not divide cleanly
     * in binary floating point — the last pieces leave a residue no stock count reconciles to zero. Holding
     * pieces rather than fractions of a pack makes the arithmetic exact; {@code DECIMAL(19,4)} rather than an
     * integer because this column also carries genuinely continuous goods (2.5 m of cable), which an integer
     * would round away silently.
     */
    private BigDecimal quantity;

    /** Held by open reservations (slice 33, Phase 6a). Available = quantity - reservedQuantity. */
    @Builder.Default
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    private String batchNo;
    private String lotNo;
    private LocalDate expiryDate;
    private BigDecimal purchasePrice;

    /**
     * #17 P2 — the exact amount paid for this batch, across all units it holds.
     *
     * <p>The batch is where allocation must happen: `purchasePrice` is per unit and therefore a rounding
     * whenever a bonus made the received quantity differ from the billed one. COGS on a partial consumption
     * is then `paidTotal x consumed / quantity`, which reconciles to the penny, instead of a rounded unit
     * cost multiplied back.
     *
     * <p>Null on every batch received before this existed, where `purchasePrice x quantity` is the total.
     */
    @Column(name = "paid_total", precision = 19, scale = 2)
    private BigDecimal paidTotal;

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
