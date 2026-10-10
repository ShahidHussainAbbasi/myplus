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

    /** Party bridge (P3): shared party/contact master id (party-service). Stamped best-effort on write (by patient
     *  phone); null until bridged — so a prescription patient links to the same party as their POS customer. */
    @Column(name = "party_id")
    private Long partyId;

    /** HMS S3b-1 (V9): DOCTOR = submitted from the clinic's consultation; COUNTER = recorded at the pharmacy. */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** The clinic token of the visit (A-040) — shown in the pharmacy list. Null for counter prescriptions. */
    @Column(name = "token_label", length = 16)
    private String tokenLabel;

    /** The visit in clinical-service. */
    @Column(name = "encounter_id")
    private Long encounterId;

    /** "enc-<encounter id>", UNIQUE per organisation: a retried Submit returns the same prescription. */
    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (status == null) status = Status.PENDING;
        if (source == null) source = SOURCE_COUNTER;
        this.createdAt = LocalDateTime.now();
    }

    public static final String SOURCE_COUNTER = "COUNTER";
    public static final String SOURCE_DOCTOR = "DOCTOR";

    public enum Status { PENDING, PARTIALLY_DISPENSED, FULLY_DISPENSED, EXPIRED, CANCELLED }
}
