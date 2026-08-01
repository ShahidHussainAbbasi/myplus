package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One end-of-year decision about one student.
 *
 * Slice 1.6 (docs/slices/edu-1.6-promotion.md). This table exists because promotion **destroys the only
 * copy of where a child was**: {@code Student.gradeId} is a single mutable column, so once a student moves
 * from Class 5 to Class 6 nothing else in the database remembers Class 5.
 *
 * <p>Without this record:
 * <ul>
 *   <li>"which class was this child in last year?" has no answer</li>
 *   <li>{@code Attendance} and {@code FeeCollection} denormalise the class name, so last year's rows
 *       disagree with the student's current class and nothing explains the gap — it reads as corruption</li>
 *   <li>an accidental batch cannot be undone</li>
 * </ul>
 *
 * <p>D3 — class and year NAMES are snapshotted as values, exactly as {@link ReportCardLine} does: a class
 * renamed next year must not retitle last year's history.
 *
 * <p>D6 — {@code (organization_id, student_enroll_no, academic_year_id)} is UNIQUE. A double-clicked
 * "Promote class" must not move a child TWO classes up, and under concurrency the constraint is the
 * guarantee rather than the pre-check (1.3 D1).
 */
@Entity
@Table(name = "promotion", uniqueConstraints = {
        @UniqueConstraint(name = "uk_promotion_student_year",
                columnNames = { "organization_id", "student_enroll_no", "academic_year_id" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "promotion_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "student_enroll_no", nullable = false)
    private String studentEnrollNo;

    /** Snapshotted — the record must stay readable after the student leaves. */
    @Column(name = "student_name")
    private String studentName;

    @Column(name = "from_grade_id")
    private Long fromGradeId;

    /** Snapshotted (D3): the class as it was NAMED at the time, not a join that follows a later rename. */
    @Column(name = "from_grade_name")
    private String fromGradeName;

    /** NULL for a retention (nowhere to go) and for a graduation (nowhere left). */
    @Column(name = "to_grade_id")
    private Long toGradeId;

    @Column(name = "to_grade_name")
    private String toGradeName;

    @Column(name = "academic_year_id", nullable = false)
    private Long academicYearId;

    @Column(name = "academic_year_name")
    private String academicYearName;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private PromotionOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PromotionStatus status;

    /**
     * Why this outcome — the policy's own words ("below the 33% pass mark: 28%"), or "decided by
     * <user>" when an admin overrode the proposal. A decision without its reason is unreviewable a year
     * later, which is precisely when it gets questioned.
     */
    @Column(name = "reason", length = 500)
    private String reason;

    /** True when an admin changed the proposed outcome — the fact that a human intervened is itself data. */
    @Column(name = "overridden", nullable = false)
    private boolean overridden;

    /** Audit: which user ran the batch. Not used for data scoping. */
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
        if (status == null) status = PromotionStatus.APPLIED;
        if (dated == null) dated = LocalDateTime.now();
    }
}
