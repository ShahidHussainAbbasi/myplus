package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One subject row on an issued report card.
 *
 * Slice 1.5, design D1 — this table stores NAMES AND NUMBERS, not foreign keys. There is deliberately no
 * {@code subjectId} or {@code examPaperId}: re-reading the name through a join would reintroduce exactly
 * the drift the snapshot exists to prevent, so renaming a subject from "EVS" to "Environmental Studies"
 * would silently retitle a card issued three years ago.
 *
 * The same reasoning as finance's immutable audit rows, and the deliberate OPPOSITE of 1.4 D4: a live
 * result should follow the current scale, an issued document should not.
 */
@Entity
@Table(name = "report_card_line")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportCardLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_card_line_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "report_card_id", nullable = false)
    private Long reportCardId;

    /** Which exam this row came from — "Mid-Term". A value, not a reference (see the class javadoc). */
    @Column(name = "exam_name")
    private String examName;

    @Column(name = "subject_name")
    private String subjectName;

    @Column(name = "max_marks")
    private Integer maxMarks;

    /** NULL when {@link #absent} — 1.3 D2's distinction survives into the printed card. */
    @Column(name = "marks_obtained")
    private Integer marksObtained;

    @Column(name = "absent", nullable = false)
    private boolean absent;

    /** The percentage as computed on the day, including the absent policy in force at the time. */
    @Column(name = "percent")
    private Double percent;

    @Column(name = "grade_name")
    private String gradeName;

    @Column(name = "gpa_points")
    private Double gpaPoints;

    /**
     * The order the row was printed in, so a reopened card reads exactly as it was issued rather than in
     * whatever order the database happens to return.
     */
    @Column(name = "sequence")
    private Integer sequence;
}
