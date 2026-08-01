package com.myplus.education.entity;

/**
 * Slice 1.6 — whether a recorded promotion is still in force (design D7).
 *
 * <pre>
 * APPLIED ──undo──► REVERSED ──run again──► APPLIED
 * </pre>
 *
 * An undo marks the row REVERSED and restores the student's class; it does not delete the row. The batch
 * HAPPENED, and a school that erases the evidence cannot explain what its records did — the same rule as
 * a superseded report card (1.5 D5) and a reversed ledger entry in finance.
 */
public enum PromotionStatus {
    APPLIED,
    REVERSED
}
