package com.myplus.education.entity;

/**
 * Slice 1.5 — the lifecycle of an ISSUED report card (design D5).
 *
 * <pre>
 * (preview: derived, never stored)
 *        │ publish
 *        ▼
 *    PUBLISHED ──republish──► SUPERSEDED   (the old row stays, readable)
 *        │
 *        └──withdraw──────► WITHDRAWN ──publish again──► PUBLISHED
 * </pre>
 *
 * There is deliberately no DRAFT. A draft card would be a second copy of the marks that goes stale the
 * moment a teacher fixes a typo; preview is a QUERY, not a record.
 *
 * A published card is never edited. Correcting one publishes version + 1 and marks the previous row
 * SUPERSEDED, because the card handed to a parent EXISTED — a school that overwrites it cannot answer
 * "what did we send you in March?", which is the question asked precisely when something has gone wrong.
 *
 * Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum ReportCardStatus {
    PUBLISHED,
    SUPERSEDED,
    WITHDRAWN
}
