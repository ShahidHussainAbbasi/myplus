package com.myplus.education.service;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.education.entity.GlOutbox;
import com.myplus.education.repository.GlOutboxRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Slice 0.1 — reliable GL posting for education, mirroring business-service's proven design.
 *
 * {@link #enqueue} writes a PENDING row IN THE CALLER'S TRANSACTION, so if the fee collection commits the GL
 * event is durable and can never be silently lost. An AFTER_COMMIT listener delivers it immediately (books stay
 * real-time), and a {@code @Scheduled} relay re-drives anything still PENDING — so finance-service being down
 * delays a journal, it never loses one. Delivery happens only after commit, so a rolled-back fee collection
 * posts no journal.
 *
 * The delivery state machine (idempotent per row, attempt counter, dead-letter) is the shared
 * {@link OutboxRelay} from common-outbox; this class supplies only the transport and the payload.
 */
@Service
@RequiredArgsConstructor
public class GlOutboxService {

    /** Fired once a row is enqueued; delivered after the caller's TX commits. */
    public record GlOutboxEvent(Long id) {}

    private final GlOutboxRepository repo;
    private final ApplicationEventPublisher events;
    private final OutboxRelay relay;

    /** Null when finance-service is unwired in this deployment — the relay then simply keeps rows PENDING. */
    @Autowired(required = false)
    private FinanceClient financeClient;

    private OutboxDelivery<GlOutbox> channel;

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "EDU-GL"; }
            public boolean available() { return financeClient != null; }
            public Optional<GlOutbox> find(Long id) { return repo.findById(id); }
            public List<GlOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public GlOutbox save(GlOutbox e) { return repo.save(e); }
            public void send(GlOutbox e) {
                // runAs: a scheduled delivery has no inbound request, so the tenant identity must come from the
                // row itself — otherwise finance would post the journal org-less.
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> financeClient.postEvent(toReq(e)));
            }
        };
    }

    /**
     * Queue a GL posting event in the caller's transaction. Never throws — a GL problem must not fail the fee
     * collection a guardian just made; the outbox is what makes that safe, because an undelivered row is retried
     * rather than dropped.
     */
    public void enqueue(PostingEventRequest req) {
        if (req == null) return;
        GlOutbox o = new GlOutbox();
        o.setEventType(req.getEventType());
        o.setEventKey(java.util.UUID.randomUUID().toString());   // one per event → finance dedups a retry
        o.setRef(req.getRef());
        o.setGrandTotal(req.getGrandTotal());
        o.setPaidAmount(req.getPaidAmount());
        o.setMethod(req.getMethod());
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setOrganizationId(CurrentUser.organizationId());
        o.setUserId(CurrentUser.userId());
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        final Long id = repo.save(o).getId();

        events.publishEvent(new GlOutboxEvent(id));
    }

    /** Deliver right after the enqueuing transaction commits; runs inline if there was no transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(GlOutboxEvent e) {
        relay.deliver(channel, e.id());
    }

    /** Retry relay — re-drives undelivered events (finance was down, a timeout, a restart mid-delivery). */
    @Scheduled(fixedDelayString = "${gl.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private PostingEventRequest toReq(GlOutbox o) {
        return PostingEventRequest.builder()
                .eventType(o.getEventType()).eventKey(o.getEventKey())
                .date(LocalDate.now()).ref(o.getRef())
                .grandTotal(o.getGrandTotal()).paidAmount(o.getPaidAmount())
                .method(o.getMethod())
                .build();
    }
}
