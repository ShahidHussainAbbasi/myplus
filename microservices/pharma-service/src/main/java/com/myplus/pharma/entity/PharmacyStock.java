package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "pharmacy_stock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PharmacyStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicine_id")
    private Medicine medicine;

    private String batchNo;
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    private BigDecimal purchasePrice;
    private BigDecimal sellingPrice;
    private String supplier;
    private LocalDate receivedDate;
    private Long userId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
