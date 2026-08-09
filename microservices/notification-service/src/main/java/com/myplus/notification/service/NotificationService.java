package com.myplus.notification.service;

import com.myplus.common.notify.EmailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends notifications (slice 33, Phase 8). The one place SMTP lives now; replaces the per-service EmailServices.
 * Best-effort: a send failure is logged and reported (returns false), never thrown, so a caller's main
 * operation isn't coupled to mail delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender mailSender;
    /**
     * Slice 105 — the delivery record. Optional so this service still starts and still SENDS if the
     * datastore is unavailable: a notice arriving matters more than its audit row (see DeliveryRecorder).
     */
    private final org.springframework.beans.factory.ObjectProvider<DeliveryRecorder> recorder;

    @Value("${spring.mail.username:noreply@myplus.com}")
    private String fromAddress;

    public boolean sendEmail(EmailRequest req) {
        return sendEmail(req, null, null, null);
    }

    /**
     * Send AND record — slice 105.
     *
     * <p>{@code source}, {@code orgId} and {@code dedupeKey} are optional: the original one-argument
     * contract still works unchanged, so no existing caller had to move. Callers that supply them get a
     * per-recipient delivery record and idempotent re-POSTs; callers that do not still send exactly as
     * before. Expand now, migrate callers later.
     *
     * <p><b>The boolean return is unchanged and still means "the mail server accepted it"</b> — not that
     * anything reached an inbox. Recording does not make that claim stronger; it means a failure is now
     * visible and retried instead of vanishing into a log line.
     */
    public boolean sendEmail(EmailRequest req, String source, Long orgId, String dedupeKey) {
        if (req == null || req.getTo() == null || req.getTo().isEmpty()) {
            throw new IllegalArgumentException("email 'to' is required");
        }
        DeliveryRecorder rec = recorder.getIfAvailable();
        com.myplus.notification.entity.NotificationBroadcast broadcast =
                rec == null ? null : rec.record(req, source, orgId, dedupeKey);
        SendResult result = deliver(req);
        if (rec != null && broadcast != null) {
            // On failure the row stays PENDING with the reason recorded, so the dispatcher retries it.
            // Before slice 105 a failure was a log line and nothing else.
            rec.settle(broadcast.getId(), result.ok(), result.error());
        }
        return result.ok();
    }

    /**
     * The outcome of one SMTP attempt, and why it failed.
     *
     * <p>Returned rather than stashed in a field: this service is a singleton handling concurrent sends, so
     * a mutable "last error" field would attribute one tenant's SMTP failure to another tenant's message.
     */
    record SendResult(boolean ok, String error) {}

    /**
     * The raw SMTP send — no recording, no retry bookkeeping.
     *
     * <p><b>Why this is separate:</b> {@link DeliveryDispatcher} re-sends deliveries that are ALREADY
     * recorded. If it called {@link #sendEmail(EmailRequest)} it would record a brand-new broadcast and a
     * fresh set of PENDING rows on every retry pass — a table that grows without bound and a queue that can
     * never drain, since each pass would create more work than it completed. The retry path must send
     * without recording; that is exactly this method.
     */
    SendResult deliver(EmailRequest req) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(req.getTo().toArray(new String[0]));
            if (req.getCc() != null && !req.getCc().isEmpty()) {
                message.setCc(req.getCc().toArray(new String[0]));
            }
            if (req.getReplyTo() != null && !req.getReplyTo().isBlank()) {
                message.setReplyTo(req.getReplyTo());
            }
            message.setSubject(req.getSubject());
            message.setText(req.getBody());
            mailSender.send(message);
            log.info("Email sent to {} (cc {}) subject='{}'", req.getTo(), req.getCc(), req.getSubject());
            return new SendResult(true, null);
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", req.getTo(), ex.getMessage(), ex);
            return new SendResult(false, ex.getMessage());
        }
    }
}
