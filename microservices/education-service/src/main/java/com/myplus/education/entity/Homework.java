package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A task set for a class — "Fractions exercise 4, Maths, due Friday, out of 20".
 *
 * Slice 2.4 (docs/slices/edu-2.4-homework.md), design D1. Directly the {@link Exam}/{@link Mark} shape from
 * 1.2/1.3: the thing set ONCE and the thing recorded PER CHILD have different lifecycles, and a flat
 * row-per-student would copy the due date onto every row where the copies drift.
 *
 * <p><b>No {@code gradeId}.</b> {@link Subject} already has a {@code @ManyToOne Grade}, so the class is
 * derived — 1.2 D2's rule. 2.1 deviated from it because a UNIQUE key cannot be built on a derived value;
 * nothing here needs one, so the rule holds unchanged.
 */
@Entity
@Table(name = "homework")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Homework {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "homework_id", unique = true, nullable = false)
    private Long id;

    /** The class is reached through this: subject → grade. Never stored again (1.2 D2). */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** Nullable per 1.1 — a school with no terms defined must keep working. */
    @Column(name = "term_id")
    private Long termId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "instructions", length = 2000)
    private String instructions;

    @Column(name = "set_on")
    private LocalDate setOn;

    /**
     * What "late" is measured against (D5). Late is DERIVED from this and never stored: extending the
     * deadline must un-late every submission that beat the new date, which a stored flag could not do.
     */
    @Column(name = "due_on")
    private LocalDate dueOn;

    /** Null means "not graded out of anything" — a task can be set without marks. */
    @Column(name = "max_marks")
    private Integer maxMarks;

    /** Audit: which user set the homework. Not used for data scoping. */
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
        if (setOn == null) setOn = LocalDate.now();
        if (dated == null) dated = LocalDateTime.now();
    }
}
