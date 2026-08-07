package com.myplus.education.entity;

/**
 * Slice 3.5 — who a {@link Notice} reaches.
 *
 * <p>A FILTER, not a recipient list (D2). Every value here is resolved against live enrolment at the moment
 * it is needed, so a child who transferred yesterday is not on today's notice.
 *
 * <p><b>@Enumerated(STRING) against a MySQL enum:</b> adding a value needs an {@code ALTER … MODIFY}
 * migration, or the insert fails with "Data truncated".
 */
public enum NoticeAudience {

    /** Everyone: guardians and students alike. The common case — a closure, a term date, a sports day. */
    WHOLE_SCHOOL,

    /** Guardians only. Fee deadlines, parents' evening, anything addressed to the adult. */
    GUARDIANS,

    /** Students only. Exam instructions, club sign-ups, anything addressed to the child. */
    STUDENTS,

    /**
     * One class, both audiences within it — reads {@link Notice#getGradeId()}.
     *
     * <p><b>A notice with this audience and a null grade must reach NOBODY.</b> Treating a missing grade as
     * "no filter" would silently promote a class notice to a whole-school one, which is the fail-open
     * direction; it has its own unit test for that reason.
     */
    ONE_CLASS
}
