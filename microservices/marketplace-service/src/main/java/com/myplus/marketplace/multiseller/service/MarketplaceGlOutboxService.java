package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.marketplace.entity.MarketplaceGlOutbox;
import com.myplus.marketplace.repository.MarketplaceGlOutboxRepository;

import jakarta.annotation.PostConstruct;

/**
 * MKT-1g — carries the operator's marketplace journals to finance-service (ruling R-MKT-3: marketplace-service keeps
 * the operational ledger, finance stays the only journal writer). The transactional-outbox shape of
 * {@code ExpenseOutboxService}: captured in the settlement's own transaction, delivered after commit, retried on a
 * schedule, dead-lettered after the relay's limit, re-drivable from {@code /outbox-health}. The event key makes a
 * redelivery a no-op in finance.
 */
@Service
public class MarketplaceGlOutboxService {

    public record Enqueued(Long id) {}

    private final MarketplaceGlOutboxRepository repo;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<FinanceClient> finance;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OutboxDelivery<MarketplaceGlOutbox> channel;

    public MarketplaceGlOutboxService(MarketplaceGlOutboxRepository repo, OutboxRelay relay,
                                      ApplicationEventPublisher events, ObjectProvider<FinanceClient> finance) {
        this.repo = repo;
        this.relay = relay;
        this.events = events;
        this.finance = finance;
    }

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "MKT-GL"; }
            public boolean available() { return finance.getIfAvailable() != null; }
            public Optional<MarketplaceGlOutbox> find(Long id) { return repo.findById(id); }
            public List<MarketplaceGlOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public MarketplaceGlOutbox save(MarketplaceGlOutbox e) { return repo.save(e); }
            public void send(MarketplaceGlOutbox e) {
                PostingEventRequest req = read(e.getPayload());
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> finance.getObject().postEvent(req));
            }
        };
    }

    /** Capture the journal in the CALLER's transaction, into {@code booksOrg}'s ledger; delivery follows the commit. */
    public void enqueue(Long booksOrg, Long booksUser, PostingEventRequest req) {
        MarketplaceGlOutbox o = new MarketplaceGlOutbox();
        o.setOrganizationId(booksOrg);
        o.setUserId(booksUser);
        o.setEventType(req.getEventType());
        o.setEventKey(req.getEventKey());
        o.setPayload(write(req));
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        events.publishEvent(new Enqueued(repo.save(o).getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(Enqueued e) {
        relay.deliver(channel, e.id());
    }

    @Scheduled(fixedDelayString = "${mkt.gl.relay-delay-ms:15000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private String write(PostingEventRequest r) {
        try { return json.writeValueAsString(r); }
        catch (Exception e) { throw new IllegalStateException("Could not record the ledger posting", e); }
    }

    PostingEventRequest read(String payload) {
        try { return json.readValue(payload, PostingEventRequest.class); }
        catch (Exception e) { throw new IllegalStateException("Unreadable ledger posting payload", e); }
    }
}
