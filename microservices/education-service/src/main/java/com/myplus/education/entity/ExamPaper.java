package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * One paper of an {@link Exam} — "Mathematics, out of 50, 14 November, 09:00".
 *
 * Slice 1.2, design D2: there is deliberately NO {@code gradeId} here. {@link Subject} already has a
 * {@code @ManyToOne Grade}, so storing the class again would create a second source of truth that can
 * contradict the first — the same reasoning that made the branch-scope slice derive a teacher's campus
 * through Grade rather than adding a column.
 *
 * D4: maxMarks/passMarks live HERE because Maths out of 100 and Drawing out of 50 within one exam is
 * entirely normal; the exam's WEIGHT lives on {@link Exam} for the opposite reason.
 */
@Entity
@Table(name = "exam_paper")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_paper_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    /** The class is reached through this: subject → grade. Never stored again (D2). */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "max_marks")
    private Integer maxMarks;

    @Column(name = "pass_marks")
    private Integer passMarks;

    @Column(name = "exam_date")
    private LocalDate examDate;

    @Column(name = "time_from")
    private LocalTime timeFrom;

    @Column(name = "time_to")
    private LocalTime timeTo;

    /** Audit: which user created this row. Not used for data scoping. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Tenant scope: denormalised from the parent exam so papers can be read without a join. */
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
