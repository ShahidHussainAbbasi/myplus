package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The lightweight clinical layer on an existing business Item (P7, slice 44): per-item safety flags used at
 * dispense. Keyed by {@code itemId} (the bridge id the sell/dispense flow uses) — no parallel product model.
 * One row per (org, item).
 */
@Entity
@Table(name = "medicine_clinical", uniqueConstraints =
        @UniqueConstraint(name = "uq_medclinical_org_item", columnNames = {"organization_id", "item_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MedicineClinical {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "medicine_name")
    private String medicineName;       // snapshot for display

    @Builder.Default
    @Column(name = "rx_required")
    private boolean rxRequired = false;

    @Builder.Default
    @Column(name = "controlled_substance")
    private boolean controlledSubstance = false;

    @Column(name = "drug_category")
    private String drugCategory;

    private LocalDateTime updated;

    @PrePersist @PreUpdate
    void touch() { updated = LocalDateTime.now(); }
}
