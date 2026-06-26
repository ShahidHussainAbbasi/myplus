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

    // slice 41 (reuse): business Item reference (same id the sell flow uses); dispense reuses the POS sale.
    @Column(name = "item_id")
    private Long itemId;
    private String medicineName;   // snapshot for the dispense record

    @Column(nullable = false)
    private int quantity;

    private BigDecimal unitPrice;
    private BigDecimal totalAmount;

    private Long dispensedBy;
    private LocalDateTime dispensedAt;

    private String patientName;
    private String notes;

    // P6 (slice 43): the trade sale this dispense was fulfilled by (the saga sale invoice) + tenant scope.
    @Column(name = "invoice_no")
    private String invoiceNo;

    @Column(name = "organization_id")
    private Long organizationId;

    // P7 (slice 44): controlled-substance dispense flag (for the controlled register/audit).
    @Builder.Default
    @Column(name = "controlled")
    private boolean controlled = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (dispensedAt == null) dispensedAt = LocalDateTime.now();
    }
}
