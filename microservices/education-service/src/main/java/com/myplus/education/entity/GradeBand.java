package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One band of a school's grading scale — "A, 80–89%, 4.0 points".
 *
 * Slice 1.4 (docs/slices/edu-1.4-grading-scales.md), design D1: bands are an ENTITY, not a setting.
 * `common-settings` stores scalars (BOOL/INT/TEXT/SELECT); a band table encoded as delimited text would
 * be a parser nobody can validate and a UI nobody can render. Same lesson as 1.1 D2 and 1.2 D6 — for
 * list-shaped configuration, the entity IS the configuration.
 *
 * Ranges are INCLUSIVE at both ends (80–89 contains both), and a valid scale covers 0–100 with no gap
 * and no overlap — enforced by {@code BandValidator}, because an overlap makes a letter ambiguous and a
 * gap makes some percentage ungradeable.
 *
 * NOTE: this carries no "is passing" flag. Pass/fail comes from {@code ExamPaper.passMarks} (1.2 D4),
 * which is per-paper and therefore more precise. Two sources of truth for pass/fail would be worse than
 * one imperfect one.
 */
@Entity
@Table(name = "grade_band")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GradeBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grade_band_id", unique = true, nullable = false)
    private Long id;

    /** As the school names it: "A+", "First Division", "9". Free text — jurisdictions differ. */
    @Column(name = "name", nullable = false)
    private String name;

    /** Inclusive lower bound, 0–100. */
    @Column(name = "min_percent", nullable = false)
    private Integer minPercent;

    /** Inclusive upper bound, 0–100. */
    @Column(name = "max_percent", nullable = false)
    private Integer maxPercent;

    /**
     * Optional GPA points for this band (4.0, 3.7 …). Null when the school does not run a GPA — a scale
     * that only produces letters is perfectly normal.
     */
    @Column(name = "gpa_points")
    private Double gpaPoints;

    /** Audit: which user created this row. Not used for data scoping. */
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
