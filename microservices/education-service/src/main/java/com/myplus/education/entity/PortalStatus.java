package com.myplus.education.entity;

/**
 * Slice 3.1 — the state of a guardian's portal access (design D3).
 *
 * <pre>
 * INVITED ──guardian signs in──► ACTIVE ──school withdraws──► REVOKED
 *                                   ▲                            │
 *                                   └──────re-invited────────────┘
 * </pre>
 *
 * <p><b>There is no SELF_REGISTERED.</b> A school invites a guardian; nobody claims a child by typing an
 * enrolment number. Self-service registration against a child's identifier is an obvious account-takeover
 * path, and the school already knows who the parents are.
 *
 * <p>{@code REVOKED} is kept rather than deleted: "this person used to have access to this child's record"
 * is exactly what an investigation needs, and the same append-only reasoning as 1.5 D5 / 2.5 D3.
 *
 * <p>Persisted with {@code @Enumerated(STRING)} against a MySQL enum column: adding a value later needs an
 * explicit {@code ALTER … MODIFY}, because ddl-auto will not do it and fails with "Data truncated".
 */
public enum PortalStatus {
    INVITED,
    ACTIVE,
    REVOKED
}
