package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One student's result on one exam paper.
 *
 * Slice 1.3 (docs/slices/edu-1.3-marks-entry.md). The first data on this platform that is a claim about a
 * CHILD which follows them for years — a wrong fee is refunded, a wrong mark is discovered at university
 * admission. Hence the unique constraint, the audit trail, and the distinction below.
 *
 * D2 — {@link #absent} is a first-class state, NOT zero. Zero means "sat the paper and scored nothing";
 * absent means they did not sit it. Conflating them corrupts every average 1.4 computes and every report
 * card 1.5 prints, and it cannot be recovered afterwards. When absent is true, marksObtained is NULL.
 *
 * D1 — {@code (exam_paper_id, student_enroll_no)} is UNIQUE, enforced by the database rather than by code
 * alone: the constraint is what makes it true under concurrency, so a double-clicked Save cannot produce
 * two marks for one child.
 */
@Entity
@Table(name = "mark", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mark_paper_student", columnNames = { "exam_paper_id", "student_enroll_no" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Mark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mark_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "exam_paper_id", nullable = false)
    private Long examPaperId;

    /**
     * Keyed by enrolment number, consistent with {@code Attendance.en} and {@code FeeCollection}.
     * Known platform-wide risk (design §7): renaming a student's enrolment number orphans their marks.
     * Inherited here rather than introduced; flagged for a future integrity pass.
     */
    @Column(name = "student_enroll_no", nullable = false)
    private String studentEnrollNo;

    /** NULL when {@link #absent} — see D2. Never write 0 to mean "did not sit". */
    @Column(name = "marks_obtained")
    private Integer marksObtained;

    @Column(name = "absent", nullable = false)
    private boolean absent;

    @Column(name = "remarks")
    private String remarks;

    /** Audit: which user entered/last changed this mark. Not used for data scoping. */
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
