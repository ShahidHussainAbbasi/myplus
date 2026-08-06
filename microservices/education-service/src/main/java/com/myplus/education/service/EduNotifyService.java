package com.myplus.education.service;

import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.NotifyOutbox;
import com.myplus.education.repository.NotifyOutboxRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Slice N1 — queues outbound notifications through the shared transactional outbox.
 *
 * Design: microservices/docs/slices/edu-N1-notification-outbox.md
 *
 * <p>Deliberately the SAME shape as {@link GlOutboxService} (0.1) and {@link EduAuditService} (1.3):
 * capture in the caller's transaction, deliver AFTER_COMMIT, retry on a schedule through the shared
 * {@link OutboxRelay}. <b>No new pattern and no new state machine</b> — the relay owns
 * {@code PENDING → SENT | FAILED}, attempts and last error.
 *
 * <p><b>Why this exists at all.</b> 2.2 shipped with a hook that logged instead of sending. Calling
 * {@code EmailService} straight from that hook would have put an inter-service HTTP call on a write path,
 * and lost the message outright whenever notification-service was unreachable — nothing would record that a
 * teacher was never told they are covering a class.
 */
@Service
@RequiredArgsConstructor
public class EduNotifyService {

    /** What happened to a requested notice. Returned to the caller so the UI can say so — design D4. */
    public enum Outcome {
        /** Queued for delivery. */
        QUEUED,
        /** The recipient has no usable email address; nobody has been told. */
        NO_EMAIL,
        /** The school has this notice switched off. */
        DISABLED
    }

    /** Fired once a row is enqueued; delivered after the caller's TX commits. */
    public record NotifyEnqueued(Long id) { }

    private final NotifyOutboxRepository repo;
    private final ApplicationEventPublisher events;
    private final OutboxRelay relay;
    private final SettingsService settingsService;

    /** Null when the mail path is unwired in this deployment — the relay then keeps rows PENDING. */
    @Autowired(required = false)
    private EmailService emailService;

    private OutboxDelivery<NotifyOutbox> channel;

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "EDU-NOTIFY"; }
            public boolean available() { return emailService != null; }
            public Optional<NotifyOutbox> find(Long id) { return repo.findById(id); }
            public List<NotifyOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public NotifyOutbox save(NotifyOutbox e) { return repo.save(e); }
            public void send(NotifyOutbox e) {
                // sendTo, NOT send: the admin-recipients CC belongs to broadcast alerts. Copying the office
                // on every cover assignment in the school is how people learn to filter the sender.
                var result = emailService.sendTo(e.getSubject(), e.getBody(), List.of(e.getRecipientEmail()));
                Object failed = result.get("failed");
                if (failed instanceof Number n && n.intValue() > 0) {
                    // Throw so the relay retries — a swallowed failure here is the exact defect this
                    // slice exists to remove (0.2a's lesson: a best-effort catch once hid a real failure).
                    throw new IllegalStateException("send failed: " + result.get("errors"));
                }
            }
        };
    }

    /**
     * Queue one notice in the caller's transaction.
     *
     * <p><b>Never throws.</b> A notification problem must not fail the decision it announces — 2.2 D6 is
     * binding: a failed message must never lose the assignment, because the school still happened. The
     * outcome is RETURNED instead, so the caller can tell the user what did not happen rather than
     * implying a send that never occurred.
     *
     * <p>The enabled flag is read HERE, on the path it governs (standard C1) — not at the screen, where a
     * direct API call would bypass it.
     */
    public Outcome queue(String eventType, String enabledSettingKey, CoverNoticeBuilder.Notice notice) {
        if (!enabled(enabledSettingKey)) return Outcome.DISABLED;
        if (notice == null || !CoverNoticeBuilder.sendable(notice.recipientEmail())) return Outcome.NO_EMAIL;

        NotifyOutbox o = new NotifyOutbox();
        o.setEventType(eventType);
        o.setRecipientEmail(notice.recipientEmail().trim());
        o.setSubject(notice.subject());
        o.setBody(notice.body());
        o.setEventKey(UUID.randomUUID().toString());
        o.setOccurredAt(LocalDateTime.now());
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setOrganizationId(CurrentUser.organizationId());
        o.setUserId(CurrentUser.userId());
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        final Long id = repo.save(o).getId();

        events.publishEvent(new NotifyEnqueued(id));
        return Outcome.QUEUED;
    }

    /**
     * Fails ON (standard C3): if the setting cannot be read, the notice is still queued.
     *
     * <p>The failure mode of an extra email is noise; the failure mode of a missing one is a teacher not
     * knowing they are covering a class. Deliberately the opposite default to 3.1's
     * {@code edu.portal.enabled}, which fails CLOSED because there the unsafe direction is disclosure.
     */
    private boolean enabled(String key) {
        try {
            return settingsService.getBool(key);
        } catch (Exception e) {
            return true;
        }
    }

    /** Deliver right after the enqueuing transaction commits; runs inline if there was no transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(NotifyEnqueued e) {
        relay.deliver(channel, e.id());
    }

    /** Retry relay — re-drives undelivered notices (notification-service down, a timeout, a restart). */
    @Scheduled(fixedDelayString = "${notify.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }
}
