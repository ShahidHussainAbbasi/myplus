package com.myplus.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HMS S3b-1 — one line of the doctor's prescription for a visit (V5). The doctor's working list: replaced as a whole
 * on Save and frozen once the visit's prescription is submitted to the pharmacy ({@code Encounter.rxId} set).
 */
@Entity
@Table(name = "encounter_rx_item")
@Getter
@Setter
@NoArgsConstructor
public class EncounterRxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "encounter_id", nullable = false)
    private Long encounterId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /** The pharmacy's catalogue Product — the same id the sale uses, so Dispense fills the cart with it. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "medicine_name", nullable = false, length = 200)
    private String medicineName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "dosage", length = 100)
    private String dosage;

    @Column(name = "frequency", length = 100)
    private String frequency;

    @Column(name = "duration", length = 100)
    private String duration;
}
