package com.myplus.education.entity;

/**
 * Slice 2.4 — what is recorded about one student's homework (design D3).
 *
 * <pre>
 * (no row)  ──student submits──►  SUBMITTED  ──teacher marks──►  MARKED
 *     │                               │
 *     └──teacher records───────────►  NOT_DONE ──────────────────┘
 * </pre>
 *
 * <p><b>"Not submitted" is the ABSENCE of a row, not a state.</b> Before anyone records anything, "no row"
 * honestly means "nothing known yet".
 *
 * <p>{@code NOT_DONE} is therefore an explicit teacher judgement. The contrast with 2.2's eagerly-written
 * {@code UNCOVERED} is deliberate: an uncovered lesson is a fact about today that must be visible BEFORE it
 * happens, so it is written up front. A missing homework only becomes a fact once someone decides the
 * deadline has passed and it counts. Writing NOT_DONE automatically at the due date would have the system
 * accuse a child on a timer.
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum SubmissionState {
    SUBMITTED,
    NOT_DONE,
    MARKED
}
