package com.myplus.education.service;

/**
 * One deliverable message: a resolved address plus rendered text.
 *
 * <h3>Why this is its own type (slice 3.5, finding D)</h3>
 *
 * N1 put {@code EduNotifyService.queue()} in front of every education send, which was right — but it typed
 * its parameter as {@code CoverNoticeBuilder.Notice}, a record belonging to 2.2's substitution feature. So
 * the shared mechanism was reachable only by callers willing to name a cover notice.
 *
 * <p>3.5 is the SECOND caller, which by this codebase's rule is exactly when a thing is generalised —
 * never speculatively, never later. The same rule produced {@code common-outbox}, {@code common-subledger},
 * {@code StaffAbsenceService} (2.3), {@code StudentVisibilityService} (1.5) and {@code PortalReadService}
 * (3.3).
 *
 * <p>{@code CoverNoticeBuilder} now returns this type, so there is <b>one</b> message shape and one send
 * path rather than two that must be kept in step.
 */
public record NotifyMessage(String recipientEmail, String subject, String body) {

    /**
     * Can this address be sent to at all?
     *
     * <p>Blank and null are the common case — a staff record entered without an email, a student who has
     * none (D-7). The {@code @} check mirrors {@code EmailService}, which silently skips anything without
     * one; without the same check here a junk value would be queued, retried and dead-lettered instead of
     * being reported at once as NO_EMAIL.
     */
    public static boolean sendable(String email) {
        return email != null && !email.isBlank() && email.contains("@");
    }
}
