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

    // slice 41 (reuse): a medicine is an existing business Item — reference it by itemId (same as the sell flow),
    // so dispensing reuses the POS Sell/saga without a parallel product model.
    @Column(name = "item_id")
    private Long itemId;
    private String medicineName;   // snapshot for display

    @Column(nullable = false)
    private int quantity;

    private String dosage;
    private String frequency;
    private String duration;

    @Builder.Default
    private int dispensedQuantity = 0;
}
