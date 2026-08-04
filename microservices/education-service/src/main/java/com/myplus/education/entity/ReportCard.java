package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One student's issued result for one term — the artefact a school hands to a guardian.
 *
 * Slice 1.5 (docs/slices/edu-1.5-report-cards.md). This is the first record on the platform whose whole
 * purpose is to STOP tracking live data.
 *
 * D1 — a report card is DERIVED until it is PUBLISHED, then SNAPSHOTTED. 1.4 made the grade derived so a
 * re-band updates live results; that is right for live results and unacceptable for issued ones. Every
 * figure below is written down at publish time and never recomputed.
 *
 * D5 — immutable and versioned. A correction publishes version + 1 and supersedes the previous row rather
 * than editing it.
 */
@Entity
@Table(name = "report_card", uniqueConstraints = {
        @UniqueConstraint(name = "uk_report_card_student_term_version",
                columnNames = { "organization_id", "student_enroll_no", "term_id", "version" })
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReportCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_card_id", unique = true, nullable = false)
    private Long id;

    /**
     * Keyed by enrolment number, consistent with {@code Mark}, {@code Attendance} and {@code FeeCollection}.
     * Known platform-wide risk (1.3 design §7): renaming a student's enrolment number orphans their history.
     * Inherited here rather than introduced.
     */
    @Column(name = "student_enroll_no", nullable = false)
    private String studentEnrollNo;

    /**
     * Snapshotted (D1) — the name as it read on the day, not a join to a student who may since have been
     * renamed, transferred or removed. A card must remain printable after the student leaves the school.
     */
    @Column(name = "student_name")
    private String studentName;

    @Column(name = "term_id", nullable = false)
    private Long termId;

    /** Snapshotted for the same reason as the student name — terms get renamed between sessions. */
    @Column(name = "term_name")
    private String termName;

    /** The class the card was issued for. Rank (below) is computed within THIS class, never school-wide. */
    @Column(name = "grade_id")
    private Long gradeId;

    @Column(name = "grade_name")
    private String gradeName;

    /** The weighted term figure (D3). Null is possible: a term with no marked papers has no percentage. */
    @Column(name = "term_percent")
    private Double termPercent;

    /** The band name AS AWARDED. Never re-derived — this is the entire point of the snapshot. */
    @Column(name = "term_grade_name")
    private String termGradeName;

    @Column(name = "term_gpa")
    private Double termGpa;

    /**
     * Position within the class, ties shared (D4). Stored even when {@code edu.reportCard.showRank} is off,
     * so that turning the setting on does not retroactively invent a rank for cards issued while it was off;
     * the SETTING decides whether it is rendered, the SNAPSHOT decides what it was.
     */
    @Column(name = "class_rank")
    private Integer classRank;

    @Column(name = "class_size")
    private Integer classSize;

    @Column(name = "attendance_present")
    private Integer attendancePresent;

    @Column(name = "attendance_total")
    private Integer attendanceTotal;

    /** 1, 2, 3 … A correction never edits; it publishes the next version (D5). */
    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReportCardStatus status;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    /** Audit: which user published this card. Not used for data scoping. */
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
        if (status == null) status = ReportCardStatus.PUBLISHED;
        if (version == 0) version = 1;
        if (issuedOn == null) issuedOn = LocalDate.now();
        if (dated == null) dated = LocalDateTime.now();
    }
}
