package com.myplus.pharma.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HMS H3b (V10) — one change the pharmacy made to a prescription: who, when, why, and the lines before → after. */
@Entity
@Table(name = "prescription_amendment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionAmendment {

    public static final String AMEND = "AMEND";
    public static final String CANCEL = "CANCEL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "prescription_id", nullable = false)
    private Long prescriptionId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** Compact JSON: [{"medicine":…, "before":{qty,dose,freq,days}|null, "after":{…}|null}] — null = added / removed. */
    @Column(name = "changes", length = 8000)
    private String changes;

    @Column(name = "amended_by")
    private Long amendedBy;

    @Column(name = "amended_at")
    private LocalDateTime amendedAt;
}
