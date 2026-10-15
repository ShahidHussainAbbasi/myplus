package com.myplus.inventory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * SSR-0a — one change of sellable on-hand (V15). Append-only: written by {@link com.myplus.inventory.service.StockLedger}
 * in the transaction that moved the stock, never updated or deleted. Column types mirror V15 exactly (ddl validate).
 */
@Entity
@Table(name = "stock_movement")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "stock_entry_id")
    private Long stockEntryId;

    @Column(name = "movement_type", nullable = false, length = 24)
    private String movementType;

    /** Signed: + in, − out. Same unit and scale as stock_levels.current_stock. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
