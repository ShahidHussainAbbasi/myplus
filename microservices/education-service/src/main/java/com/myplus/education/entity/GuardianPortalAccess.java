package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A guardian's permission to sign in to the guardian portal.
 *
 * Slice 3.1 (docs/slices/edu-3.1-guardian-portal.md). <b>The first record that lets someone OUTSIDE the
 * school authenticate</b>, which is what makes this the most security-sensitive table in the schema.
 *
 * <h3>What this row does NOT contain: the children</h3>
 *
 * There is deliberately no child list here. "My children" is <b>derived on every request</b> from
 * {@code Student.guardianId} (design D1). Storing it would create a second source of truth that goes stale
 * the moment a child transfers, a guardian link is corrected, or a sibling enrols — and a stale copy of an
 * <i>access</i> list is not a caching bug, it is a stranger reading a child's record.
 *
 * <h3>Invitation, not self-registration (D3)</h3>
 *
 * The school creates this row for a guardian it already knows. Nobody claims a child by typing an
 * enrolment number.
 *
 * <p><b>Known gap, tracked in the programme:</b> {@link #email} comes from {@code Guardian.email}, which is
 * unverified free text. Invitation-only limits the damage, but a typo in the guardian record invites a
 * stranger. Email verification is required before this goes to a real school.
 */
@Entity
@Table(name = "guardian_portal_access", uniqueConstraints = {
        // One access row per guardian per tenant. The constraint is what makes that true under a
        // double-clicked invite (1.3 D1's lesson).
        @UniqueConstraint(name = "uk_portal_access_guardian",
                columnNames = { "organization_id", "guardian_id" }),
        // Slice 3.3 — the same rule stated for ANY subject. Student rows leave guardian_id NULL, so the
        // key above no longer covers them; this one does. The TABLE is still called
        // guardian_portal_access because renaming a live table is what D5 forbids — see V25's header.
        @UniqueConstraint(name = "uk_portal_access_subject",
                columnNames = { "organization_id", "subject_type", "subject_id" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GuardianPortalAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "guardian_portal_access_id", unique = true, nullable = false)
    private Long id;

    /**
     * Slice 3.3 — WHO this access is about: a guardian, or a student in their own right.
     *
     * <p>Defaults to {@code GUARDIAN} so every row written before 3.3, and any caller that has not been
     * taught about students, keeps meaning exactly what it did.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 32)
    @Builder.Default
    private PortalSubjectType subjectType = PortalSubjectType.GUARDIAN;

    /** The {@code Guardian.id} or {@code Student.id}, per {@link #subjectType}. Backfilled in V25. */
    @Column(name = "subject_id")
    private Long subjectId;

    /**
     * The guardian this access is about — {@code null} for a STUDENT row.
     *
     * <p>NULLABLE since V25, and deliberately not reused to hold a student id: guardian 5 and student 5 in
     * one org would then collide in {@code uk_portal_access_guardian}, giving two unrelated people one
     * unique key. Kept as the read path for 3.1's code until a later slice retires it in favour of
     * {@link #subjectId} (V25 drops nothing — standard D5).
     */
    @Column(name = "guardian_id")
    private Long guardianId;

    /**
     * The login identity, snapshotted from the guardian at invitation time.
     *
     * <p>Snapshotted deliberately: if the guardian record's email is later corrected, the existing access
     * must NOT silently start authorising a different address. Re-inviting is the explicit act that moves
     * access to a new email.
     */
    @Column(name = "email", nullable = false)
    private String email;

    /** Snapshotted so a revoked row stays readable after the guardian record changes. */
    @Column(name = "guardian_name")
    private String guardianName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PortalStatus status;

    @Column(name = "invited_on")
    private LocalDate invitedOn;

    @Column(name = "activated_on")
    private LocalDate activatedOn;

    @Column(name = "revoked_on")
    private LocalDate revokedOn;

    /** Audit: which staff member invited or revoked. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: which organization this row belongs to. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    @PrePersist
    void prePersist() {
        if (status == null) status = PortalStatus.INVITED;
        if (invitedOn == null) invitedOn = LocalDate.now();
        if (dated == null) dated = LocalDateTime.now();
    }
}
