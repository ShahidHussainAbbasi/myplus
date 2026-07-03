package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "prescription_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    // M5 (slice 100): a medicine is a catalog Product — reference it by productId (the same id the POS sell flow now
    // uses), so dispensing reuses the POS Sell/saga on the single Product master.
    @Column(name = "product_id")
    private Long productId;
    private String medicineName;   // snapshot for display

    @Column(nullable = false)
    private int quantity;

    private String dosage;
    private String frequency;
    private String duration;

    @Builder.Default
    private int dispensedQuantity = 0;
}
