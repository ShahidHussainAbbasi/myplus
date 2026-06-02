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

    @Column(name = "user_id", nullable = false)
    private Long userId;

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
