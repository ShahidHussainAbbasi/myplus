package com.myplus.education.entity;

/**
 * Slice 2.3 — the life of a leave request (design D6).
 *
 * <pre>
 * PENDING ──approve──► APPROVED ──cancel──► CANCELLED
 *    │                                          ▲
 *    └──reject──► REJECTED ─────────────────────┘   (kept, never deleted)
 * </pre>
 *
 * <p>A REJECTED request is contested data — "I asked and was refused" is exactly what gets disputed months
 * later — so it is audited and never deleted. Same rule as a superseded report card (1.5 D5) and a reversed
 * promotion (1.6 D7).
 *
 * <p>When {@code edu.leave.requireApproval} is off a request is created APPROVED directly; the state machine
 * is unchanged, only the entry point.
 */
public enum LeaveRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
