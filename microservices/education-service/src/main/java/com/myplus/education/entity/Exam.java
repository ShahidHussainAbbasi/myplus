package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An examination event — "Term 2 Mid-Term".
 *
 * Slice 1.2 (docs/slices/edu-1.2-examinations.md). The thing marks are recorded AGAINST: a mark is
 * meaningless without the paper it belongs to and the maximum it is out of.
 *
 * D1: the EVENT is separate from its papers. A flat row-per-subject cannot say "the mid-term counts
 * for 30% of Term 2" without repeating that weight on every subject row, where the copies drift apart.
 *
 * D2: one exam naturally spans the whole school — its papers reference subjects, and a Subject already
 * knows its Grade, so "Class 5's datesheet" is a FILTER rather than a separate exam.
 */
@Entity
@Table(name = "exam")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_id", unique = true, nullable = false)
    private Long id;

    /** As the school names it: "Mid-Term", "Pre-Board", "Unit Test 3". */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * D6: free text, deliberately NOT a setting or a catalog table. Nothing in the code branches on
     * the value; the form offers a datalist so it stays discoverable without being constrained.
     */
    @Column(name = "type")
    private String type;

    /**
     * D3: NOT NULL. 1.1 made term_id nullable everywhere so a school without terms keeps working —
     * exams are where that stops, because "which term does this count toward?" has no safe default.
     */
    @Column(name = "term_id", nullable = false)
    private Long termId;

    /**
     * D4: how much this exam contributes to the term (30 = 30%). Lives here, not on the paper —
     * it is a property of the exam, not of Mathematics. Totals are WARNED about, never blocked.
     */
    @Column(name = "weight_percent")
    private Integer weightPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExamStatus status;

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
        if (status == null) status = ExamStatus.DRAFT;
        if (dated == null) dated = LocalDateTime.now();
    }
}
