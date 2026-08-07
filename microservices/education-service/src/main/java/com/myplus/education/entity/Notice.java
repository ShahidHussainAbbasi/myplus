package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One notice or circular from the school to its community.
 *
 * Slice 3.5 (docs/slices/edu-3.5-notices.md).
 *
 * <h3>The RECORD is the deliverable, not the email (finding C)</h3>
 *
 * When this slice was first planned, email was the school's only channel, so a notice could only <i>be</i>
 * an email. Slices 3.1 and 3.3 shipped two authenticated surfaces in front of families since — so a notice
 * is now a record the portals render, and email is one <b>delivery</b> of it.
 *
 * <p>That distinction is the point of the entity. <b>An emailed-only notice is unrecoverable:</b> a family
 * that deleted it, or never received it, has nothing to return to — and "we sent it" against "we never got
 * it" is exactly the dispute a school needs a record to settle.
 *
 * <h3>The audience is a FILTER, never a stored list (D2)</h3>
 *
 * {@link #audience} plus an optional {@link #gradeId} is resolved against live enrolment every time it is
 * needed. There is no recipient table: a stored list is a copy of an access decision and goes stale the
 * moment a child transfers or a guardian link is corrected. 3.1 D1 refused a stored child list for the same
 * reason, and on a safety notice a stale list is worse than anywhere else here.
 *
 * <h3>Two states, and no workflow (D1)</h3>
 *
 * DRAFT reaches nobody; PUBLISHED is visible and delivered. 2.5 established that this domain does not want
 * approval chains, and a notice needs exactly one boundary rather than a state machine.
 */
@Entity
@Table(name = "notice")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    /**
     * The message itself.
     *
     * <p><b>A bounded VARCHAR, not {@code @Lob}/TEXT — and that is the house standard, not a preference.</b>
     * Every long-text column in this service is one: {@code behaviour_note.description} and
     * {@code homework.instructions} are VARCHAR(2000), {@code homework_submission.feedback} VARCHAR(1000).
     *
     * <p>The first cut of this field used {@code @Lob}, which Hibernate maps to CLOB and then validates
     * against MySQL as {@code tinytext} — so the service refused to start against a column declared TEXT
     * ("found [text], but expecting [tinytext]"). Schema validation caught it at boot, which is the control
     * working: 4000 characters is longer than any circular a school writes, and it keeps the row inline.
     */
    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    /** WHO this reaches. Only {@link NoticeAudience#ONE_CLASS} reads {@link #gradeId}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 32)
    @Builder.Default
    private NoticeAudience audience = NoticeAudience.WHOLE_SCHOOL;

    /**
     * The class this is for, when {@link #audience} is ONE_CLASS. Null otherwise.
     *
     * <p><b>A null grade must never match ONE_CLASS.</b> That is the fail-open case in this slice — it
     * would turn a class notice into a whole-school one — and it has its own unit test.
     */
    @Column(name = "grade_id")
    private Long gradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private NoticeStatus status = NoticeStatus.DRAFT;

    /** Set when it is published — the date families see, and the portal's sort key. */
    @Column(name = "published_on")
    private LocalDate publishedOn;

    /**
     * Held at the top of the portal list until this date, then ordinary.
     *
     * <p>A DATE rather than a boolean on purpose: a pinned flag stays pinned until somebody remembers to
     * clear it, and nobody does. Exam week un-pins itself.
     */
    @Column(name = "pinned_until")
    private LocalDate pinnedUntil;

    /** Audit: who wrote it. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;
}
