package com.myplus.notification.entity;

/**
 * Slice 105 — what happened to ONE recipient.
 *
 * <p>Per-recipient rather than per-broadcast, because "failed 2" is not an answer a school can act on and
 * "here are the 2" is.
 */
public enum DeliveryStatus {
    /** Accepted and not yet delivered. The dispatcher's queue is exactly this set. */
    PENDING,
    /** Handed to the mail server successfully. NOT proof of inbox arrival — see NotificationService. */
    SENT,
    /** Every attempt failed. `last_error` says why, so support has something better than a shrug. */
    FAILED
}
