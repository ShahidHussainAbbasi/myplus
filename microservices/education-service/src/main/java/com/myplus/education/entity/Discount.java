package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "discount")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Discount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "discount_id", unique = true, nullable = false)
    private Long id;

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    private String name;

    @Column(name = "discount_in")
    private String di;

    private Integer amount;

    private LocalDate startDate;

    private LocalDate endDate;

    private String description;

    private String referenceName;

    private String referenceMobile;

    private String status;
}
