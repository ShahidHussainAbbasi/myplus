package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.business_service.entity.AuditOutbox;
import com.myplus.business_service.repository.AuditOutboxRepo;
import com.myplus.business_service.service.outbox.OutboxDelivery;
import com.myplus.business_service.service.outbox.OutboxRelay;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.commerce.contracts.dto.AuditEventRequest;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.GatewayIdentityForwarding;

import lombok.RequiredArgsConstructor;

/**
 * Audit #6: business-service's producer facade for the standalone audit-service. {@link #record} captures the event
 * in the CALLER'S transaction (audit_outbox — atomic with the business change, never lost, never a rolled-back event),
 * then an AFTER_COMMIT listener delivers it to audit-service; a {@link #flushPending @Scheduled} relay re-drives
 * anything still PENDING, impersonating the tenant via {@link GatewayIdentityForwarding#runAs}. Delivery state machine
 * is the shared {@link OutboxRelay} (same as GlOutboxService #4) — delivery only AFTER commit, so a rolled-back op
 * logs nothing.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String SOURCE = "business";

    /** Fired once an audit event is enqueued; delivered after the caller's TX commits. */
    public record AuditEnqueued(Long id) {}

    private final AuditOutboxRepo repo;
    private final RequestUtil requestUtil;
    private final ApplicationEventPublisher events;
    private final OutboxRelay relay;   // shared delivery state machine

    @Autowired(required = false)
    private AuditClient auditClient;   // shared audit-service; null if unwired in this deployment

    /** The audit transport strategy for the shared relay: runAs the tenant → POST to audit-service. */
    private OutboxDelivery<AuditOutbox> channel;

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "Audit"; }
            public boolean available() { return auditClient != null; }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
            public void send(AuditOutbox e) {
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(), () -> auditClient.record(toReq(e)));
            }
        };
    }

    /** Record one money/stock event (atomic with the caller's tx; delivered after commit + retried by the relay). */
    public void record(String action, String entityType, String entityRef, BigDecimal amount, String details) {
        if (action == null) return;
        AuthenticatedUser u = requestUtil.getCurrentUser();
        AuditOutbox o = new AuditOutbox();
        o.setAction(action);
        o.setEntityType(entityType);
        o.setEntityRef(entityRef);
        o.setAmount(amount);
        o.setDetails(details != null && details.length() > 500 ? details.substring(0, 500) : details);
        o.setEventKey(UUID.randomUUID().toString());
        o.setOccurredAt(LocalDateTime.now());
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setOrganizationId(u != null ? u.getOrganizationId() : null);
        o.setUserId(u != null ? u.getUserId() : null);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        final Long id = repo.save(o).getId();
        events.publishEvent(new AuditEnqueued(id));
    }

    /** Deliver right after the enqueuing business TX commits; runs inline if there was no TX (fallbackExecution). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(AuditEnqueued e) {
        relay.deliver(channel, e.id());
    }

    /** Attempt to deliver one outbox row to audit-service (POSTED/FAILED rows are skipped). */
    public void tryDeliver(Long id) {
        relay.deliver(channel, id);
    }

    /** Retry relay — re-drives undelivered audit events (mirrors GlOutboxService / SagaRecoveryRelay). */
    @Scheduled(fixedDelayString = "${audit.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private AuditEventRequest toReq(AuditOutbox o) {
        return AuditEventRequest.builder()
                .sourceService(SOURCE).action(o.getAction())
                .entityType(o.getEntityType()).entityRef(o.getEntityRef())
                .amount(o.getAmount()).details(o.getDetails())
                .eventKey(o.getEventKey()).occurredAt(o.getOccurredAt())
                .build();
    }
}
