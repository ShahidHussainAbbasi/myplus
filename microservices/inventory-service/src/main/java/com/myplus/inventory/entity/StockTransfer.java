package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_transfers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockTransfer {

    public enum TransferStatus { PENDING, COMPLETED, CANCELLED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_warehouse_id")
    private Warehouse fromWarehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_warehouse_id")
    private Warehouse toWarehouse;

    // Product master lives in catalog-service (slice 33, Phase 5b) — referenced by id.
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Float quantity;

    @Builder.Default
    private LocalDateTime transferDate = LocalDateTime.now();

    private Long transferredBy;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransferStatus status = TransferStatus.PENDING;

    @Column(length = 1000)
    private String notes;
}
