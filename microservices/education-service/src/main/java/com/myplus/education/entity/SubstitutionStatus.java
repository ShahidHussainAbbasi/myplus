package com.myplus.education.entity;

/**
 * Slice 2.2 — what was decided about one lesson on one day (design D5).
 *
 * <p><b>{@code UNCOVERED} is a first-class state, not the absence of a row.</b> A lesson nobody can cover
 * means a class will be unsupervised — the single most important thing on the morning screen. Representing
 * it as "no row" makes it invisible to every query and impossible to report on, so the day's list could
 * never say <i>"Period 3, Class 5A — nobody assigned"</i> and the term could never answer "how often are we
 * short?".
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum SubstitutionStatus {
    /** A cover teacher is assigned. */
    ASSIGNED,
    /** The lesson needs cover and has none. Recorded explicitly — see the class javadoc. */
    UNCOVERED,
    /** The absence was cleared, or the cover was withdrawn. Kept rather than deleted. */
    CANCELLED
}
