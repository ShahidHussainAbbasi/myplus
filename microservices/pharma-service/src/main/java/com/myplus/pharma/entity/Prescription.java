package com.myplus.pharma.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "prescriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String patientName;

    private String patientPhone;
    private String doctorName;
    private String doctorLicense;
    private LocalDate prescribedDate;
    private LocalDate validUntil;
    private String diagnosis;
    private String notes;
    private Long dispensedBy;
    private LocalDateTime dispensedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "organization_id")
    private Long organizationId;     // P5 (slice 41): tenant scope

    private Long userId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (status == null) status = Status.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public enum Status { PENDING, PARTIALLY_DISPENSED, FULLY_DISPENSED, EXPIRED, CANCELLED }
}
