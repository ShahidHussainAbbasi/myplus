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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Column(nullable = false)
    private Float quantity;

    private String batchNo;
    private String lotNo;
    private LocalDate expiryDate;
    private BigDecimal purchasePrice;

    @Builder.Default
    private LocalDateTime entryDate = LocalDateTime.now();

    private Long supplierId;

    @Column(length = 1000)
    private String notes;
}
