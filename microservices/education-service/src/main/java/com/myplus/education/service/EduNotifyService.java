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
    public Outcome queue(String eventType, String enabledSettingKey, NotifyMessage notice) {
        if (!enabled(enabledSettingKey)) return Outcome.DISABLED;
        if (notice == null || !NotifyMessage.sendable(notice.recipientEmail())) return Outcome.NO_EMAIL;

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
     * Slice 3.5 — queue the SAME text to many recipients. Returns how many rows were enqueued.
     *
     * <p><b>One outbox row per recipient, deliberately.</b> The alternative — one row carrying a recipient
     * list — makes a single bad address fail the whole broadcast on retry, and gives the school no way to
     * see which family was not reached. Per-recipient rows mean a failure is isolated, retried on its own,
     * and visible.
     *
     * <p>Addresses that cannot be sent to are skipped rather than failing the publish: they are the norm in
     * this domain (D-7 records that students largely have none), and <b>the notice is readable in the portal
     * regardless</b> — which is the whole of finding C. The count returned is therefore "queued", never
     * "sent", and callers must say so.
     *
     * <p>The enabled flag is read once here rather than per recipient: it governs the broadcast, not the
     * address.
     */
    public int queueAll(String eventType, String enabledSettingKey, String subject, String body,
                        java.util.Collection<String> recipients) {
        if (enabledSettingKey != null && !enabled(enabledSettingKey)) return 0;
        if (recipients == null || recipients.isEmpty()) return 0;
        int queued = 0;
        for (String email : recipients) {
            // The gate is applied once, above — passing null here skips the per-recipient re-read of a
            // setting that governs the broadcast, not the address.
            if (queue(eventType, null, new NotifyMessage(email, subject, body)) == Outcome.QUEUED) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * Ungated broadcast — for a caller that has <b>no feature switch of its own</b>.
     *
     * <p>Exists for slice 3.5's migration of {@code sendAlerts}. That screen has never had an on/off
     * setting, and giving it one while moving it onto the outbox would change the MECHANISM and the POLICY
     * in a single commit — which is how a migration gets blamed for a behaviour nobody asked for. It also
     * must not borrow {@code edu.notify.notices}: turning notices off would then silently stop alerts, two
     * unrelated features sharing one switch.
     *
     * <p>If schools later want an alerts switch, it is a registered setting and a one-line change here.
     */
    public int queueAll(String eventType, String subject, String body,
                        java.util.Collection<String> recipients) {
        return queueAll(eventType, null, subject, body, recipients);
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
