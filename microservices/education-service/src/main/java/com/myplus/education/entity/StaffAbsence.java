package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One teacher is not in on one day.
 *
 * Slice 2.2 (docs/slices/edu-2.2-substitution.md), design D1.
 *
 * <h3>Why this exists here and not in 2.3</h3>
 *
 * The programme's own note said <i>"2.3 staff attendance is what makes a substitution necessary — so the
 * order is a dependency chain"</i>, which read literally puts 2.3 <b>before</b> 2.2. There is no
 * staff-attendance data today: {@code Attendance} is student-only ({@code en} / {@code sn}).
 *
 * <p>Rather than reorder, or couple a five-second operational screen to an unbuilt HR model (the coupling
 * 1.3 D6 refused), this slice owns the one fact a substitution actually needs — <i>this teacher is out
 * today</i>.
 *
 * <h3>Deliberately thin, and that is the design</h3>
 *
 * <b>No leave type, no balance, no approval.</b> Those belong to 2.3, and putting a {@code type} column here
 * would create a second vocabulary 2.3 then has to reconcile. {@link #leaveId} is reserved so 2.3 can link
 * its own record without a migration that rewrites history.
 *
 * <p><b>Carried requirement for 2.3:</b> it must WRITE these rows from its leave/register flow, not create a
 * parallel absence concept. Recorded in the programme's carried-requirements table.
 */
@Entity
@Table(name = "staff_absence", uniqueConstraints = {
        // A teacher is absent once per day. The constraint is what makes that true under a double-clicked
        // "mark absent" — the 1.3 D1 / 1.6 D6 lesson.
        @UniqueConstraint(name = "uk_staff_absence_day",
                columnNames = { "organization_id", "staff_id", "absence_date" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StaffAbsence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "staff_absence_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    /** Snapshotted so the day's list stays readable after a staff member leaves. */
    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "absence_date", nullable = false)
    private LocalDate absenceDate;

    /** Free text — "sick", "training". NOT an enum: the vocabulary is 2.3's to define, not this slice's. */
    @Column(name = "reason")
    private String reason;

    /** Reserved for 2.3: the leave record that produced this absence. Null for a manually-marked day. */
    @Column(name = "leave_id")
    private Long leaveId;

    /** Audit: which user marked the absence. Not used for data scoping. */
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
        if (dated == null) dated = LocalDateTime.now();
    }
}
