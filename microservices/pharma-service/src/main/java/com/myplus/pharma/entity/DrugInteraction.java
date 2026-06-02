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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicine1_id")
    private Medicine medicine1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicine2_id")
    private Medicine medicine2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String recommendation;

    public enum Severity { MILD, MODERATE, SEVERE }
}
