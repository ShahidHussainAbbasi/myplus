package com.myplus.notification.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.notify.EmailRequest;
import com.myplus.notification.entity.Channel;
import com.myplus.notification.entity.DeliveryStatus;
import com.myplus.notification.entity.NotificationBroadcast;
import com.myplus.notification.entity.NotificationDelivery;
import com.myplus.notification.repository.NotificationBroadcastRepository;
import com.myplus.notification.repository.NotificationDeliveryRepository;

/**
 * Slice 105 — writes down what was asked for, and what happened to each person.
 *
 * <p>This is the half of G3 that persistence buys: before it, a failed alert to 300 guardians was a log
 * line, and "did this family get the closure notice?" had no answer anywhere on the platform.
 *
 * <h3>Recording must never break sending</h3>
 *
 * A caller asked to notify someone. If this service cannot write the record — the database is down, a
 * column is too small — the RIGHT outcome is still to attempt the send, not to refuse it. So every method
 * here is best-effort and returns what it managed; {@link NotificationService} treats a null record as
 * "unrecorded but still deliverable".
 *
 * <p>The opposite choice is defensible for money. It is wrong for a closure notice, where the message
 * arriving matters more than the audit row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryRecorder {

    private final NotificationBroadcastRepository broadcastRepo;
    private final NotificationDeliveryRepository deliveryRepo;

    /**
     * Record a request and one row per recipient. Returns the broadcast, or null if it could not be
     * written at all.
     *
     * <p><b>Idempotent on {@code dedupeKey}.</b> Both this service and its callers retry — education's
     * relay re-POSTs on a timeout — so a duplicate is a certainty, not a risk. A repeat returns the
     * ORIGINAL broadcast and creates no new deliveries, which is what makes a re-POST safe.
     */
    @Transactional
    public NotificationBroadcast record(EmailRequest req, String source, Long orgId, String dedupeKey) {
        try {
            if (dedupeKey != null && !dedupeKey.isBlank()) {
                Optional<NotificationBroadcast> existing = broadcastRepo.findByDedupeKey(dedupeKey);
                if (existing.isPresent()) {
                    log.info("Duplicate broadcast ignored (dedupeKey={}) — returning the original", dedupeKey);
                    return existing.get();
                }
            }

            // Deduplicated recipients: one person named in both `to` and `cc` is one delivery, not two.
            Set<String> recipients = new LinkedHashSet<>();
            if (req.getTo() != null) req.getTo().stream().filter(this::sendable).forEach(recipients::add);
            if (req.getCc() != null) req.getCc().stream().filter(this::sendable).forEach(recipients::add);

            NotificationBroadcast b = broadcastRepo.save(NotificationBroadcast.builder()
                    .organizationId(orgId)
                    .source(source)
                    .subject(trim(req.getSubject(), 255))
                    .body(trim(req.getBody(), 4000))
                    .channel(Channel.EMAIL)
                    .dedupeKey(dedupeKey == null || dedupeKey.isBlank() ? null : dedupeKey)
                    .totalRecipients(recipients.size())
                    .createdAt(LocalDateTime.now())
                    .build());

            List<NotificationDelivery> rows = new ArrayList<>();
            for (String r : recipients) {
                rows.add(NotificationDelivery.builder()
                        .broadcastId(b.getId()).organizationId(orgId)
                        .recipient(trim(r, 255)).channel(Channel.EMAIL)
                        .status(DeliveryStatus.PENDING).attempts(0)
                        .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                        .build());
            }
            deliveryRepo.saveAll(rows);
            return b;
        } catch (DataIntegrityViolationException e) {
            // The unique key won a race with another thread's identical broadcast. Return theirs.
            if (dedupeKey != null) {
                Optional<NotificationBroadcast> existing = broadcastRepo.findByDedupeKey(dedupeKey);
                if (existing.isPresent()) return existing.get();
            }
            log.warn("Could not record broadcast (integrity): {}", e.getMessage());
            return null;
        } catch (Exception e) {
            // See the class javadoc: recording must never stop a send.
            log.error("Could not record broadcast — the send will still be attempted", e);
            return null;
        }
    }

    /** Mark every delivery of a broadcast with the outcome of one attempt. */
    @Transactional
    public void settle(Long broadcastId, boolean success, String error) {
        if (broadcastId == null) return;
        try {
            for (NotificationDelivery d : deliveryRepo.findByBroadcastIdOrderByIdAsc(broadcastId)) {
                markOne(d, success, error);
            }
        } catch (Exception e) {
            log.error("Could not settle deliveries for broadcast {}", broadcastId, e);
        }
    }

    /** Mark ONE delivery — used by the retry dispatcher, which works row by row. */
    @Transactional
    public void settleOne(NotificationDelivery d, boolean success, String error) {
        try {
            markOne(d, success, error);
        } catch (Exception e) {
            log.error("Could not settle delivery {}", d.getId(), e);
        }
    }

    private void markOne(NotificationDelivery d, boolean success, String error) {
        d.setAttempts((d.getAttempts() == null ? 0 : d.getAttempts()) + 1);
        d.setUpdatedAt(LocalDateTime.now());
        if (success) {
            d.setStatus(DeliveryStatus.SENT);
            d.setSentAt(LocalDateTime.now());
            d.setLastError(null);
        } else {
            // Stays PENDING so the dispatcher retries; only the attempt cap moves it to FAILED, and that
            // decision lives in the dispatcher rather than here.
            d.setLastError(trim(error, 1000));
        }
        deliveryRepo.save(d);
    }

    /** An address worth attempting — the same rule the education side uses, so behaviour matches. */
    private boolean sendable(String email) {
        return email != null && !email.isBlank() && email.contains("@");
    }

    /** Truncate rather than let an oversized value abort the insert — the record is worth more intact. */
    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
