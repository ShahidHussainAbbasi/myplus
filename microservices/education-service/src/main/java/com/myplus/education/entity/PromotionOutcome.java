package com.myplus.education.entity;

/**
 * Slice 1.6 — what was decided about one student at the end of a year (design D5).
 *
 * <p>All three are DECISIONS, and all three are recorded. A retention writes a row even though nothing
 * moves: "we considered this child and kept them back" and "we never got to this child" are different
 * facts, and only a recorded decision can tell them apart next year.
 *
 * <p>There is no UNDECIDED value here. Undecided is a state of the *proposal*, not of the record — a
 * student the school has not decided about simply has no promotion row for that year.
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs
 * an explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum PromotionOutcome {
    PROMOTED,
    RETAINED,
    /** Left the top of the school. Never a deletion — a school is asked about its alumni for decades. */
    GRADUATED
}
