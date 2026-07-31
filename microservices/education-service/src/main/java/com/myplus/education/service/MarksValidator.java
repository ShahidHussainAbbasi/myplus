package com.myplus.education.service;

/**
 * Slice 1.3 (D3) — validates ONE marks row against its paper.
 *
 * Pure and static so the bounds × absent × missing matrix is testable without a database, the same way
 * {@code TermService.resolveCurrent} (1.1) and {@code ExamLockGuard} (1.2) are.
 *
 * Returns a message rather than throwing because the caller validates 40 rows and reports them per
 * student (D3): an exception would abort the batch, which is exactly the all-or-nothing behaviour this
 * slice exists to avoid — one bad cell must never discard 39 correct entries.
 */
public final class MarksValidator {

    private MarksValidator() { }

    /**
     * @param marks    the entered value, null when nothing was typed
     * @param absent   whether the student was marked absent
     * @param maxMarks the paper's ceiling, null when the paper never set one
     * @return a human reason the row is invalid, or {@code null} when it is fine
     */
    public static String validate(Integer marks, boolean absent, Integer maxMarks) {
        if (absent) {
            // D2: absent is not a score. A value alongside the tick is contradictory input, and silently
            // dropping one of the two would decide for the teacher which they meant.
            if (marks != null) {
                return "marked absent but also given marks — clear one of the two";
            }
            return null;
        }
        if (marks == null) {
            // Not an error: a blank row is "not marked yet". The teacher may be saving a partial sheet,
            // and forcing a value would push them to type 0, which D2 says means something else entirely.
            return null;
        }
        if (marks < 0) {
            return "marks cannot be negative (" + marks + ")";
        }
        if (maxMarks != null && marks > maxMarks) {
            return marks + " exceeds the maximum of " + maxMarks + " for this paper";
        }
        return null;
    }

    /** True when the row carries something worth persisting — a mark, an absence, or a remark. */
    public static boolean hasContent(Integer marks, boolean absent, String remarks) {
        return marks != null || absent || (remarks != null && !remarks.trim().isEmpty());
    }
}
