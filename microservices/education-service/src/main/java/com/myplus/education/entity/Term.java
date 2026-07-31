package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A term (or semester, or quarter) within an {@link AcademicYear}.
 *
 * Slice 1.1, design decision D2: there is deliberately NO {@code edu.term.count} setting — the ENTITY
 * is the configuration. A school running two semesters creates two rows; one running four quarters
 * creates four. Nothing in the code assumes a number.
 *
 * D3: "current term" is DERIVED from dates by {@code TermService.currentTerm()}, never stored as a
 * flag that a nightly job has to maintain. {@link #pinnedCurrent} is the one explicit override, for
 * the real case of a school holding a term open past its end date to finish entering marks.
 */
@Entity
@Table(name = "term")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Term {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "academic_year_id", nullable = false)
    private Long academicYearId;

    /** As the school names it: "Term 1", "Semester A", "Autumn". */
    @Column(name = "name", nullable = false)
    private String name;

    /** Ordering within the year (1, 2, 3 …). Used for display and for "the most recently ended". */
    @Column(name = "sequence")
    private Integer sequence;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * D3 override: when true this term wins over the date comparison. Deliberate and visible in the
     * UI — a silently wrong date is not.
     */
    @Column(name = "pinned_current", nullable = false)
    private boolean pinnedCurrent;

    /** Audit: which user created this row. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: which organization this row belongs to. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;
}
