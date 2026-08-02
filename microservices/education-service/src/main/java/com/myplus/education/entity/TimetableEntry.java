package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * One scheduled lesson: this class, this period, this day — this subject, this teacher, this room.
 *
 * Slice 2.1 (docs/slices/edu-2.1-timetable.md). The first structure in the system that says <b>who is
 * where, when</b>; 2.2 substitution reads it and 2.3 staff attendance is what makes a substitution
 * necessary.
 *
 * <h3>D2 — why {@link #gradeId} is stored even though it is derivable</h3>
 *
 * {@link Subject} already has a {@code @ManyToOne Grade}, so per <b>1.2 D2</b> ("never store the class
 * twice") this column should not exist. This slice deliberately deviates, for two reasons that did not
 * apply to {@code ExamPaper}:
 *
 * <ol>
 *   <li><b>The class is the primary query axis.</b> "Show 5A's timetable" is the busiest read in Phase 2.
 *       Deriving it means joining through {@code subject} on every render of the busiest screen.</li>
 *   <li><b>The clash constraint needs a column.</b> A class cannot be in two places at once, and per
 *       1.3 D1 / 1.6 D6 the only thing that makes that true under a double-clicked save is a UNIQUE key —
 *       which cannot be built on a derived value.</li>
 * </ol>
 *
 * <b>The cost is a second source of truth for the class</b>, and it is contained rather than ignored:
 * every write validates that this {@code gradeId} equals the subject's grade and refuses otherwise
 * ({@code ClashDetector.gradeMatchesSubject}). A copy that is checked on every write is a cache; a copy
 * that is never checked is the drift 1.2 D2 warned about. <b>Do not remove that check.</b>
 */
@Entity
@Table(name = "timetable_entry", uniqueConstraints = {
        // A teacher is in exactly one place in a slot…
        @UniqueConstraint(name = "uk_tt_staff_slot",
                columnNames = { "organization_id", "term_id", "day_of_week", "period_id", "staff_id" }),
        // …and so is a class. Both are enforceable only because gradeId is stored (D2) and a slot is an
        // equality rather than a time range (D1).
        @UniqueConstraint(name = "uk_tt_grade_slot",
                columnNames = { "organization_id", "term_id", "day_of_week", "period_id", "grade_id" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timetable_entry_id", unique = true, nullable = false)
    private Long id;

    /**
     * Nullable, per 1.1's rule that a school with no terms keeps working.
     *
     * <p><b>Consequence for the UNIQUE keys above:</b> MySQL does not treat NULLs as equal, so for a
     * tenant with no terms the constraints silently do not fire. {@code ClashDetector} refuses the clash
     * regardless — it is the first line of defence and, for those tenants, the only one.
     */
    @Column(name = "term_id")
    private Long termId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private DayOfWeek dayOfWeek;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** Derived from the subject, stored for the reasons in the class javadoc, and validated on every write. */
    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    /** Nullable: a slot can be timetabled before the teacher is decided. */
    @Column(name = "staff_id")
    private Long staffId;

    /**
     * A free-text label, NOT a foreign key (D6). {@code Grade.room} is a bare Long and there is no room
     * master to reference; inventing one would be a second slice smuggled into this one. It is also why a
     * room clash only WARNS.
     */
    @Column(name = "room")
    private String room;

    /** Audit: which user scheduled this. Not used for data scoping. */
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
