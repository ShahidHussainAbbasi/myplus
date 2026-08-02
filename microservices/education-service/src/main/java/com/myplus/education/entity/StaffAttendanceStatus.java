package com.myplus.education.entity;

/**
 * Slice 2.3 — how a staff member's day is recorded.
 *
 * <p>{@code LEAVE} is distinct from {@code ABSENT} on purpose: both mean "not teaching today", but only one
 * is authorised, and a school counts them differently at year end. Conflating them would be the same
 * mistake 1.3 D2 refused when it kept "absent" apart from a zero mark — once merged, the distinction is
 * unrecoverable.
 *
 * <p>{@code LATE} is DERIVED at marking time from {@code Staff.timeIn} + {@code edu.attendance.staffGraceMinutes},
 * not typed by a clerk, so the threshold is one org-wide policy rather than a judgement per row.
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum StaffAttendanceStatus {
    PRESENT,
    ABSENT,
    LATE,
    HALF_DAY,
    /** Authorised: there is an approved LeaveRequest behind this day. */
    LEAVE
}
