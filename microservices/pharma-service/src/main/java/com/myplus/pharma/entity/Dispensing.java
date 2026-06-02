package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "dispensing")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dispensing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_item_id")
    private PrescriptionItem prescriptionItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicine_id")
    private Medicine medicine;

    @Column(nullable = false)
    private int quantity;

    private BigDecimal unitPrice;
    private BigDecimal totalAmount;

    private Long dispensedBy;
    private LocalDateTime dispensedAt;

    private String patientName;
    private String notes;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (dispensedAt == null) dispensedAt = LocalDateTime.now();
    }
}
