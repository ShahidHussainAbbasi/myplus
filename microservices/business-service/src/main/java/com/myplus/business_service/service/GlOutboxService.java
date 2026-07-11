package com.myplus.business_service.service;

import com.myplus.business_service.entity.GlOutbox;
import com.myplus.business_service.repository.GlOutboxRepo;
import com.myplus.business_service.service.gl.GlEventPublisher;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Audit #4: reliable GL posting via a transactional outbox. {@link #enqueue} writes a PENDING row IN THE CALLER'S
 * TX (so if the sale/purchase/return/edit commits, the GL event is durable — never silently lost), then an
 * AFTER_COMMIT event listener delivers it immediately (books stay real-time). A {@link #flushPending @Scheduled}
 * relay re-drives anything still PENDING. The actual transport is behind the {@link GlEventPublisher} seam
 * (HTTP today; swappable to a broker later). Delivery only happens AFTER commit → no journal for a rolled-back
 * business change.
 */
@Service
@RequiredArgsConstructor
public class GlOutboxService {

    private static final Logger LOG = LoggerFactory.getLogger(GlOutboxService.class);
    private static final int MAX_ATTEMPTS = 20;

    /** Fired once an outbox row is enqueued; delivered after the caller's TX commits. */
    public record GlOutboxEvent(Long id) {}

    private final GlOutboxRepo repo;
    private final RequestUtil requestUtil;
    private final ApplicationEventPublisher events;
    private final GlEventPublisher publisher;   // the GL transport seam (HTTP now; broker later)

    /** Queue a GL posting event in the caller's transaction; delivered after commit + retried by the relay. */
    public void enqueue(PostingEventRequest req) {
        if (req == null) return;
        AuthenticatedUser u = requestUtil.getCurrentUser();
        GlOutbox o = new GlOutbox();
        o.setEventType(req.getEventType());
        o.setEventKey(java.util.UUID.randomUUID().toString());   // Audit #5: dedup key for finance (one per event)
        o.setRef(req.getRef());
        o.setGrandTotal(req.getGrandTotal());
        o.setSubTotal(req.getSubTotal());
        o.setTaxTotal(req.getTaxTotal());
        o.setCost(req.getCost());
        o.setPaidAmount(req.getPaidAmount());
        o.setMethod(req.getMethod());
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setOrganizationId(u != null ? u.getOrganizationId() : null);
        o.setUserId(u != null ? u.getUserId() : null);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        final Long id = repo.save(o).getId();

        // Deliver AFTER the business TX commits (so a rolled-back change never posts a journal). fallbackExecution on
        // the listener handles the no-active-transaction case by running inline.
        events.publishEvent(new GlOutboxEvent(id));
    }

    /** Deliver right after the enqueuing business TX commits (real-time books); runs inline if there was no TX. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(GlOutboxEvent e) {
        tryDeliver(e.id());
    }

    /** Attempt to deliver one outbox row to finance-service (idempotent-ish: POSTED/FAILED rows are skipped). */
    public void tryDeliver(Long id) {
        GlOutbox o = repo.findById(id).orElse(null);
        if (o == null || "POSTED".equals(o.getStatus()) || "FAILED".equals(o.getStatus())) return;
        if (!publisher.isAvailable()) return;
        try {
            publisher.publish(toReq(o), o.getUserId(), o.getOrganizationId());
            o.setStatus("POSTED");
            o.setUpdatedAt(LocalDateTime.now());
            repo.save(o);
        } catch (Exception ex) {
            o.setAttempts((o.getAttempts() == null ? 0 : o.getAttempts()) + 1);
            o.setLastError(String.valueOf(ex.getMessage()));
            if (o.getAttempts() >= MAX_ATTEMPTS) o.setStatus("FAILED");   // dead-letter for manual review
            o.setUpdatedAt(LocalDateTime.now());
            repo.save(o);
            LOG.warn("GL outbox delivery failed for event {} (attempt {}); will retry", id, o.getAttempts(), ex);
        }
    }

    /** Retry relay — re-drives undelivered GL events (mirrors SagaRecoveryRelay). */
    @Scheduled(fixedDelayString = "${gl.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        for (GlOutbox o : repo.findTop100ByStatusOrderByIdAsc("PENDING")) tryDeliver(o.getId());
    }

    private PostingEventRequest toReq(GlOutbox o) {
        return PostingEventRequest.builder()
                .eventType(o.getEventType()).eventKey(o.getEventKey()).date(LocalDate.now()).ref(o.getRef())
                .grandTotal(o.getGrandTotal()).subTotal(o.getSubTotal()).taxTotal(o.getTaxTotal())
                .cost(o.getCost()).paidAmount(o.getPaidAmount()).method(o.getMethod())
                .build();
    }
}
