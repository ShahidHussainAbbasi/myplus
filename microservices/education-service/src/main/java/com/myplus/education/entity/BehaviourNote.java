package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One recorded account of a student's conduct.
 *
 * Slice 2.5 (docs/slices/edu-2.5-discipline-log.md). <b>The most sensitive data this platform holds</b>, and
 * different in kind from everything else in Phase 2:
 *
 * <ul>
 *   <li>it is an <b>opinion</b>, not a measurement — a mark can be re-marked from the paper, but "was rude
 *       in class" cannot be re-derived from anything; it is one person's account;</li>
 *   <li>it <b>follows a child for years</b> and is read by people who were not there;</li>
 *   <li>it is <b>contested</b> — by the student, the parent, sometimes the school itself.</li>
 * </ul>
 *
 * <h3>Append-only (D3)</h3>
 *
 * There is no update-description path and no delete endpoint. A correction writes a NEW note and marks the
 * original {@link NoteStatus#SUPERSEDED}, linked. A silently edited account is worse than no record: it
 * carries the authority of a contemporaneous note without being one.
 *
 * <h3>Author is not the typist (D4)</h3>
 *
 * {@link #recordedByStaffId} is who witnessed or reported it; {@link #userId} is who typed it. An office
 * clerk entering what a teacher reported is normal, and defaulting the author to the session user would
 * silently attribute an account to the wrong person — exactly when it is being disputed.
 *
 * <h3>No UNIQUE key, deliberately</h3>
 *
 * Every other Phase 2 table has one. Here two genuine incidents for one child on one day is ordinary, so
 * uniqueness would be a bug rather than a guarantee.
 */
@Entity
@Table(name = "behaviour_note")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BehaviourNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "behaviour_note_id", unique = true, nullable = false)
    private Long id;

    /**
     * Keyed by enrolment number, consistent with {@code Mark}, {@code Attendance} and {@code ReportCard}.
     * Known platform-wide risk (1.3 §7): renaming an enrolment number orphans history. Inherited, not new.
     */
    @Column(name = "student_enroll_no", nullable = false)
    private String studentEnrollNo;

    /** Snapshotted — a note must stay readable years later, after the student has left. */
    @Column(name = "student_name")
    private String studentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private BehaviourType type;

    /**
     * D1 — free text with a datalist, NOT a taxonomy table. Nothing branches on the value, and the same
     * reasoning as 1.2 D6's exam type applies: a school that invents twenty categories produces a log
     * nobody can summarise. The value is in the description, not the label.
     */
    @Column(name = "category")
    private String category;

    /** When it happened — not when it was typed, which is {@link #dated}. */
    @Column(name = "occurred_on")
    private LocalDate occurredOn;

    /** The account itself. Never updated after save (D3). */
    @Column(name = "description", length = 2000, nullable = false)
    private String description;

    /** What the school did about it, if anything. Recording an outcome, not running a workflow. */
    @Column(name = "action", length = 1000)
    private String action;

    /** D4 — who witnessed or reported it. */
    @Column(name = "recorded_by_staff_id")
    private Long recordedByStaffId;

    @Column(name = "recorded_by_staff_name")
    private String recordedByStaffName;

    /**
     * D5 — a RECORDED FACT, not an action this slice performs. The school ticks it once they have spoken
     * to the parent. Nothing is sent: the notification path is still a stub across 2.2 and 2.4, and the
     * most sensitive data in the system is the worst place to bolt on a third half-wired sender.
     */
    @Column(name = "parent_informed", nullable = false)
    private boolean parentInformed;

    @Column(name = "parent_informed_on")
    private LocalDate parentInformedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NoteStatus status;

    /** Set on the ORIGINAL when a correction supersedes it, so the trail reads forwards. */
    @Column(name = "superseded_by_note_id")
    private Long supersededByNoteId;

    /** D4 — who TYPED it. Distinct from the author above; both matter in a dispute. */
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
        if (status == null) status = NoteStatus.ACTIVE;
        if (type == null) type = BehaviourType.NEUTRAL;
        if (occurredOn == null) occurredOn = LocalDate.now();
        if (dated == null) dated = LocalDateTime.now();
    }
}
