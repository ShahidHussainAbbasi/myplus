package com.myplus.clinical.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HMS S3a — one consultation (V3). One per token (uq_encounter_token). Vitals are typed and range-checked by
 * {@code EncounterRules} before they reach here. Editable while OPEN; COMPLETED is final.
 */
@Entity
@Table(name = "encounter")
@Getter @Setter @NoArgsConstructor
public class Encounter {

    public static final String OPEN = "OPEN";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "token_id", nullable = false)
    private Long tokenId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "doctor_user_id")
    private Long doctorUserId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = OPEN;

    @Column(name = "chief_complaint", length = 500)
    private String chiefComplaint;

    @Column(name = "bp_systolic")
    private Integer bpSystolic;

    @Column(name = "bp_diastolic")
    private Integer bpDiastolic;

    @Column(name = "pulse")
    private Integer pulse;

    @Column(name = "temperature_f", precision = 4, scale = 1)
    private BigDecimal temperatureF;

    @Column(name = "spo2")
    private Integer spo2;

    @Column(name = "weight_kg", precision = 5, scale = 1)
    private BigDecimal weightKg;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** HMS S3b-1 (V5): the pharma-service prescription the Submit made. Set once; the lines are frozen from then. */
    @Column(name = "rx_id")
    private Long rxId;

    @Column(name = "rx_submitted_at")
    private LocalDateTime rxSubmittedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
