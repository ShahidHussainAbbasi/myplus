package com.myplus.clinical.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HMS S1 — a patient of one clinic (V1). Clinical identity only: the MRN and demographics. The person is
 * party-service's ({@link #partyId}), the pharmacy customer is business-service's ({@link #customerId}); both are
 * linked after the patient commits and may briefly be null while a link is pending.
 *
 * <p>Columns are typed to match V1 exactly ({@code ddl-auto=validate}): {@code status} and {@code sex} are VARCHAR
 * held as String, never a Java enum on a MySQL ENUM.
 */
@Entity
@Table(name = "patient",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_patient_phone", columnNames = {"organization_id", "phone_key", "family_seq"}),
                @UniqueConstraint(name = "uq_patient_mrn", columnNames = {"organization_id", "mrn"})
        },
        indexes = { @Index(name = "idx_patient_party", columnList = "organization_id,party_id") })
@Getter @Setter @NoArgsConstructor
public class Patient {

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "mrn", nullable = false, length = 32)
    private String mrn;

    /** As the front desk should read it back: 03001234567. */
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /** PartyKeys.phoneKey — the last 10 digits; what one-patient-per-phone is enforced on. */
    @Column(name = "phone_key", nullable = false, length = 10)
    private String phoneKey;

    @Column(name = "family_seq", nullable = false)
    private Integer familySeq = 0;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "cnic", length = 15)
    private String cnic;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** M | F | O, or null. */
    @Column(name = "sex", length = 1)
    private String sex;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "party_id")
    private Long partyId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
