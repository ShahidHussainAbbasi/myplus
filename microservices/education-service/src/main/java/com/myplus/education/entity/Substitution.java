package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Who covers one lesson on one day.
 *
 * Slice 2.2 (docs/slices/edu-2.2-substitution.md), design D2.
 *
 * <h3>Why this carries a DATE as well as a timetable entry</h3>
 *
 * 2.1 D5 made the timetable a <b>weekly pattern</b> — the same {@link TimetableEntry} recurs every Tuesday.
 * A substitution must therefore say <i>which</i> Tuesday, or cover silently applies to all of them. This is
 * the first place that weekly-pattern decision has a consequence, and it is why 2.1 deliberately did not
 * version the timetable.
 *
 * <p>Covering "Mrs Khan on Tuesday" is also not one decision but one per period she teaches — different
 * people cover different periods — so the row is per LESSON, not per teacher-day.
 */
@Entity
@Table(name = "substitution", uniqueConstraints = {
        // One decision per lesson per day. Under a double-clicked assign the constraint is the guarantee,
        // not the pre-check (1.3 D1).
        @UniqueConstraint(name = "uk_substitution_lesson_day",
                columnNames = { "organization_id", "timetable_entry_id", "sub_date" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Substitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "substitution_id", unique = true, nullable = false)
    private Long id;

    /** The recurring lesson being covered. The date below says which occurrence. */
    @Column(name = "timetable_entry_id", nullable = false)
    private Long timetableEntryId;

    @Column(name = "sub_date", nullable = false)
    private LocalDate subDate;

    @Column(name = "absent_staff_id")
    private Long absentStaffId;

    /** NULL while {@link SubstitutionStatus#UNCOVERED} — the state that means a class is unsupervised. */
    @Column(name = "cover_staff_id")
    private Long coverStaffId;

    /**
     * Snapshotted for the printed morning list: it gets pinned to a staffroom wall and must stay readable
     * without a join, and after a staff member leaves.
     */
    @Column(name = "cover_staff_name")
    private String coverStaffName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SubstitutionStatus status;

    /** Audit: which user assigned the cover. Not used for data scoping. */
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
        if (status == null) status = SubstitutionStatus.UNCOVERED;
        if (dated == null) dated = LocalDateTime.now();
    }
}
