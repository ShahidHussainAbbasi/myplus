package com.myplus.education.entity;

/**
 * Slice 3.5 — a {@link Notice} is either not yet visible, or visible and delivered.
 *
 * <p><b>Two states, deliberately, and no workflow (D1).</b> 2.5 established that this domain does not want
 * approval chains — they get bypassed, and a state nobody advances is a notice nobody sends. Publishing is
 * the single act, gated at ADMIN tier because addressing the whole school community is a policy decision.
 */
public enum NoticeStatus {

    /** Written, saved, and reaching nobody. Invisible to both portals; the gate asserts that. */
    DRAFT,

    /** Visible in the portals and queued for delivery. Both happen in one transaction. */
    PUBLISHED
}
