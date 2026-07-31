package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A school's academic year — "2026-27".
 *
 * Slice 1.1 (docs/slices/edu-1.1-academic-year-term.md). The spine every later academic record hangs
 * off: an exam belongs to a term, a term belongs to a year, promotion happens at the end of a year.
 *
 * NOT to be confused with {@code Student.yearStart}/{@code yearEnd}, which are per-student enrolment
 * dates — they say when THIS child joined, not what year the SCHOOL is running.
 */
@Entity
@Table(name = "academic_year")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "academic_year_id", unique = true, nullable = false)
    private Long id;

    /** As the school names it: "2026-27", "AY 2026". Free text — schools differ. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Audit: which user created this row. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: which organization this row belongs to. */
    @Column(name = "organization_id")
    private Long organizationId;

    private String status;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;
}
