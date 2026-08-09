package com.myplus.notification.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.notify.EmailRequest;
import com.myplus.notification.entity.DeliveryStatus;
import com.myplus.notification.entity.NotificationBroadcast;
import com.myplus.notification.entity.NotificationDelivery;
import com.myplus.notification.repository.NotificationBroadcastRepository;
import com.myplus.notification.repository.NotificationDeliveryRepository;

/**
 * Slice 105 — re-drives deliveries that have not succeeded yet.
 *
 * <h3>Why this exists</h3>
 *
 * Gap G3: delivery was "synchronous, unrecorded and NEVER RETRIED". A transient SMTP failure — a timeout, a
 * throttle, a restart mid-send — lost the message permanently, and nothing anywhere said so. Two other
 * slices built their own outboxes rather than depend on that.
 *
 * <h3>This does NOT duplicate education's outbox</h3>
 *
 * Each hop retries its own failure, which is correct layering:
 * <pre>
 *   education notify_outbox : the REQUEST survives an education restart (queued atomically with the notice)
 *   this dispatcher         : the SMTP DELIVERY is retried, per recipient, with the outcome recorded
 * </pre>
 * Neither can do the other's job: education cannot know an SMTP attempt failed after its POST returned, and
 * this service cannot know a notice was written but never handed over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryDispatcher {

    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationBroadcastRepository broadcastRepo;
    private final NotificationService notificationService;
    private final DeliveryRecorder recorder;

    /**
     * How many times one recipient is attempted before being given up on.
     *
     * <p>Bounded because a permanently bad address — a typo'd domain, a closed mailbox — would otherwise
     * occupy the queue forever and be retried until the end of time, crowding out real sends. Five
     * attempts across five minutes distinguishes a transient outage from a dead address well enough.
     */
    @Value("${notify.dispatch.max-attempts:5}")
    private int maxAttempts;

    /** Kept small so one pass cannot hold a connection across hundreds of SMTP round trips. */
    @Value("${notify.dispatch.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${notify.dispatch.delay-ms:60000}")
    public void dispatchPending() {
        List<NotificationDelivery> due;
        try {
            due = deliveryRepo.findDueForDispatch(
                    DeliveryStatus.PENDING, maxAttempts, PageRequest.of(0, batchSize));
        } catch (Exception e) {
            log.error("Dispatcher could not read the queue", e);
            return;
        }
        if (due.isEmpty()) return;

        log.info("Dispatcher retrying {} pending delivery/deliveries", due.size());
        for (NotificationDelivery d : due) {
            try {
                NotificationBroadcast b = broadcastRepo.findById(d.getBroadcastId()).orElse(null);
                if (b == null) {
                    // The request is gone but the delivery row remains — retrying forever would be worse
                    // than admitting we cannot. Record why rather than leaving it pending indefinitely.
                    recorder.settleOne(d, false, "broadcast " + d.getBroadcastId() + " no longer exists");
                    giveUpIfExhausted(d);
                    continue;
                }
                EmailRequest req = new EmailRequest();
                req.setTo(List.of(d.getRecipient()));
                req.setSubject(b.getSubject());
                req.setBody(b.getBody());

                // deliver(), NOT sendEmail(): this delivery is already recorded. Going through the
                // recording path would write a NEW broadcast and a fresh batch of PENDING rows on every
                // retry pass — each pass creating more work than it finished, so the queue could never
                // drain and the table would grow without bound.
                NotificationService.SendResult r = notificationService.deliver(req);
                boolean ok = r.ok();
                recorder.settleOne(d, ok, ok ? null : r.error());
                if (!ok) giveUpIfExhausted(d);
            } catch (Exception e) {
                // One bad row must not stop the batch — the other recipients are still waiting.
                log.error("Delivery {} failed to dispatch", d.getId(), e);
                recorder.settleOne(d, false, e.getMessage());
                giveUpIfExhausted(d);
            }
        }
    }

    /**
     * Move a delivery to FAILED once it is out of attempts, so it leaves the queue and becomes visible.
     *
     * <p>A row stuck PENDING forever is indistinguishable from one about to succeed, which is exactly the
     * ambiguity the school is asking about when they ring.
     */
    @Transactional
    public void giveUpIfExhausted(NotificationDelivery d) {
        try {
            NotificationDelivery fresh = deliveryRepo.findById(d.getId()).orElse(null);
            if (fresh == null || fresh.getStatus() == DeliveryStatus.SENT) return;
            if (fresh.getAttempts() != null && fresh.getAttempts() >= maxAttempts) {
                fresh.setStatus(DeliveryStatus.FAILED);
                deliveryRepo.save(fresh);
                log.warn("Giving up on delivery {} to {} after {} attempts: {}",
                        fresh.getId(), fresh.getRecipient(), fresh.getAttempts(), fresh.getLastError());
            }
        } catch (Exception e) {
            log.error("Could not mark delivery {} failed", d.getId(), e);
        }
    }
}
