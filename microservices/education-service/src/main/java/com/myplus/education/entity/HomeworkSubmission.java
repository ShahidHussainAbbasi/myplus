package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What is recorded about one student's homework.
 *
 * Slice 2.4, design D2 — <b>these rows are created LAZILY.</b> Setting homework for a class of 40 writes
 * zero of them; a row appears when there is something to record. Pre-seeding the roster would assert 40
 * facts that are not yet true, and every student who joins the class afterwards would be silently missing
 * from it. The roster comes from {@code StudentVisibilityService} at read time and submissions are joined
 * onto it — the same shape the marks grid uses (1.3).
 */
@Entity
@Table(name = "homework_submission", uniqueConstraints = {
        // One row per child per task. Under a double-clicked save the constraint is the guarantee, not the
        // pre-check — the lesson from 1.3 D1, re-applied.
        @UniqueConstraint(name = "uk_homework_submission_student",
                columnNames = { "organization_id", "homework_id", "student_enroll_no" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HomeworkSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "homework_submission_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "homework_id", nullable = false)
    private Long homeworkId;

    /**
     * Keyed by enrolment number, consistent with {@code Mark}, {@code Attendance} and {@code ReportCard}.
     * Known platform-wide risk (1.3 §7): renaming a student's enrolment number orphans their history.
     * Inherited here rather than introduced.
     */
    @Column(name = "student_enroll_no", nullable = false)
    private String studentEnrollNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private SubmissionState state;

    /** Compared against the homework's due date to derive lateness (D5). Never a stored `late` flag. */
    @Column(name = "submitted_on")
    private LocalDate submittedOn;

    /** Null until graded. A graded zero and an ungraded submission are different facts (1.3 D2's rule). */
    @Column(name = "marks_obtained")
    private Integer marksObtained;

    @Column(name = "feedback", length = 1000)
    private String feedback;

    /**
     * D6 — the attachment, when there is one. <b>Nothing in this slice writes this column.</b>
     *
     * <p>An opaque reference rather than a local {@code Attachment} table: storing files in
     * education-service is the duplication §1.2 forbids, and the standards already note that a blob column
     * in MySQL will not scale. A single reference is what a {@code document-service} client will populate,
     * so the schema does not change when blocking decision <b>D-5</b> lands.
     *
     * <p>A column nothing writes is normally a smell — the same shape as the unreachable {@code Student.fee}
     * found in slice B §8. It is justified only because the alternative is migrating a table that will
     * already hold real data, and it is tracked in the programme's carried-requirements table so a future
     * audit reads it as a decision rather than a defect.
     */
    @Column(name = "document_ref")
    private String documentRef;

    /** Audit: which user recorded this. Not used for data scoping. */
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
