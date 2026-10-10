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

/** HMS S3b-2 — one line of a prescription template (V6). The same shape as a visit's line (EncounterRxItem). */
@Entity
@Table(name = "rx_template_item")
@Getter
@Setter
@NoArgsConstructor
public class RxTemplateItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

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
