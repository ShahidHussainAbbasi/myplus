package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "drug_interactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrugInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // M5 (slice 100): interactions are between two catalog Products (by productId), consistent with the sell flow.
    @Column(name = "product_id1")
    private Long productId1;

    @Column(name = "product_id2")
    private Long productId2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String recommendation;

    // P7 (slice 44): tenant scope.
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    public enum Severity { MILD, MODERATE, SEVERE }
}
