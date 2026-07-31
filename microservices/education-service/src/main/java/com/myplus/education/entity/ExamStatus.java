package com.myplus.education.entity;

/**
 * Slice 1.2 — the lifecycle of an exam DEFINITION (design D5).
 *
 * <pre>
 * DRAFT ──publish──► PUBLISHED ──first mark entered (1.3)──► LOCKED
 *                        ▲                                     │
 *                        └────────── explicit unlock ──────────┘
 * </pre>
 *
 * The point of LOCKED is that changing {@code maxMarks} after marks exist silently restates every
 * student's percentage — no error, no trace, and report cards that disagree with the marksheets
 * already printed. 1.3 audits marks EDITS; for the definition a lock is both cheaper and stronger.
 *
 * Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs
 * an explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum ExamStatus {
    DRAFT,
    PUBLISHED,
    LOCKED
}
