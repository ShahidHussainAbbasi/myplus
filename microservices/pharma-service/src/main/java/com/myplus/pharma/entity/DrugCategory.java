package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "drug_categories")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DrugCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;
    private Long parentId;

    @Builder.Default
    private boolean isActive = true;
}
