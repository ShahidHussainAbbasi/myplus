package com.myplus.education.entity;

/**
 * Slice 2.5 — whether a behaviour note is the current account (design D3).
 *
 * <pre>
 * ACTIVE ──corrected──► SUPERSEDED   (kept, readable, linked to its replacement)
 * </pre>
 *
 * <p>There is no DELETED and no edit-in-place. The whole value of a behaviour record is that it says what
 * someone reported AT THE TIME; a silently edited account is worse than no record, because it carries the
 * authority of a contemporaneous note without being one.
 *
 * <p>Same rule as a superseded report card (1.5 D5) and a reversed promotion (1.6 D7) — but it matters more
 * here than anywhere it has been applied, because this is the record that gets disputed years later.
 */
public enum NoteStatus {
    ACTIVE,
    SUPERSEDED
}
