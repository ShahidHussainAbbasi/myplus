package com.myplus.clinical.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HMS S3a — a doctor's note (V3). APPEND-ONLY: written once, never updated or deleted (no setter is ever called after
 * the insert, and the service has no update or delete path). A correction is a new note with {@link #amendsNoteId}.
 */
@Entity
@Table(name = "clinical_note")
@Getter @Setter @NoArgsConstructor
public class ClinicalNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "encounter_id", nullable = false)
    private Long encounterId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "author_user_id")
    private Long authorUserId;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "amends_note_id")
    private Long amendsNoteId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
