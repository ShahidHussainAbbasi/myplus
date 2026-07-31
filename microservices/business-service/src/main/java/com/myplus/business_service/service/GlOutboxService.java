package com.myplus.business_service.service;

import com.myplus.business_service.entity.GlOutbox;
import com.myplus.business_service.repository.GlOutboxRepo;
import com.myplus.business_service.service.gl.GlEventPublisher;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.security.AuthenticatedUser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Audit #4: reliable GL posting via a transactional outbox. {@link #enqueue} writes a PENDING row IN THE CALLER'S
 * TX (so if the sale/purchase/return/edit commits, the GL event is durable — never silently lost), then an
 * AFTER_COMMIT event listener delivers it immediately (books stay real-time). A {@link #flushPending @Scheduled}
 * relay re-drives anything still PENDING. The delivery state machine is the shared {@link OutboxRelay}; the transport
 * is behind the {@link GlEventPublisher} seam (HTTP today; swappable to a broker later). Delivery only happens AFTER
 * commit → no journal for a rolled-back business change.
 */
@Service
@RequiredArgsConstructor
public class GlOutboxService {

    /** Fired once an outbox row is enqueued; delivered after the caller's TX commits. */
    public record GlOutboxEvent(Long id) {}

    private final GlOutboxRepo repo;
    private final RequestUtil requestUtil;
    private final ApplicationEventPublisher events;
    private final GlEventPublisher publisher;   // the GL transport seam (HTTP now; broker later)
    private final OutboxRelay relay;            // shared delivery state machine

    /** The GL transport strategy for the shared relay: deliver through the publisher seam. */
    private OutboxDelivery<GlOutbox> channel;

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "GL"; }
            public boolean available() { return publisher.isAvailable(); }
            public Optional<GlOutbox> find(Long id) { return repo.findById(id); }
            public List<GlOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public GlOutbox save(GlOutbox e) { return repo.save(e); }
            public void send(GlOutbox e) { publisher.publish(toReq(e), e.getUserId(), e.getOrganizationId()); }
        };
    }

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
        o.setStoreCredit(req.getStoreCredit());   // SF-5 Model B: carry the store-credit split to finance (GL 2200)
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
        relay.deliver(channel, e.id());
    }

    /** Attempt to deliver one outbox row to finance-service (idempotent-ish: POSTED/FAILED rows are skipped). */
    public void tryDeliver(Long id) {
        relay.deliver(channel, id);
    }

    /** Retry relay — re-drives undelivered GL events (mirrors SagaRecoveryRelay). */
    @Scheduled(fixedDelayString = "${gl.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private PostingEventRequest toReq(GlOutbox o) {
        return PostingEventRequest.builder()
                .eventType(o.getEventType()).eventKey(o.getEventKey()).date(LocalDate.now()).ref(o.getRef())
                .grandTotal(o.getGrandTotal()).subTotal(o.getSubTotal()).taxTotal(o.getTaxTotal())
                .cost(o.getCost()).paidAmount(o.getPaidAmount()).method(o.getMethod())
                .storeCredit(o.getStoreCredit())
                .build();
    }
}
