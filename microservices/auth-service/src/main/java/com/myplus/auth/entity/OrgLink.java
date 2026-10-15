package com.myplus.auth.entity;

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

/** HC-1c (V37) — a Hospital / Clinic linked to a pharmacy: REQUESTED → ACTIVE → ENDED. Never deleted, never re-opened. */
@Entity
@Table(name = "org_link")
@Getter @Setter @NoArgsConstructor
public class OrgLink {

    public static final String REQUESTED = "REQUESTED";
    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_org_id", nullable = false)
    private Long clinicOrgId;

    @Column(name = "pharmacy_org_id", nullable = false)
    private Long pharmacyOrgId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "ended_by")
    private Long endedBy;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "ended_by_org_id")
    private Long endedByOrgId;

    @Column(name = "end_reason", length = 255)
    private String endReason;

    /** Is {@code orgId} one of the two sides? */
    public boolean involves(Long orgId) {
        return orgId != null && (orgId.equals(clinicOrgId) || orgId.equals(pharmacyOrgId));
    }
}
