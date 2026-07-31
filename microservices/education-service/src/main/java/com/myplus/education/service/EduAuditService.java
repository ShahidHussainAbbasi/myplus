package com.myplus.education.service;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.commerce.contracts.dto.AuditEventRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.education.entity.AuditOutbox;
import com.myplus.education.repository.AuditOutboxRepository;
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
 * Slice 1.3 (D5) — records marks events to the shared audit-service.
 *
 * Named {@code EduAuditService} rather than {@code AuditService} because business-service already owns
 * that name; keeping them distinct makes cross-service greps unambiguous.
 *
 * Deliberately the SAME shape as {@link GlOutboxService} and business-service's audit path — capture in
 * the caller's transaction, deliver AFTER_COMMIT, retry on a schedule through the shared
 * {@link OutboxRelay}. No new pattern, no direct HTTP call on the write path.
 *
 * Why an outbox and not a plain call: a teacher's marks save must not fail because audit-service is
 * down, and the event must not be lost either. An outbox is the only shape that gives both.
 */
@Service
@RequiredArgsConstructor
public class EduAuditService {

    private static final String SOURCE = "education";

    /** Fired once a row is enqueued; delivered after the caller's TX commits. */
    public record AuditEnqueued(Long id) {}

    private final AuditOutboxRepository repo;
    private final ApplicationEventPublisher events;
    private final OutboxRelay relay;

    /** Null when audit-service is unwired in this deployment — the relay then keeps rows PENDING. */
    @Autowired(required = false)
    private AuditClient auditClient;

    private OutboxDelivery<AuditOutbox> channel;

    @PostConstruct
    void initChannel() {
        channel = new OutboxDelivery<>() {
            public String name() { return "EDU-AUDIT"; }
            public boolean available() { return auditClient != null; }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
            public void send(AuditOutbox e) {
                // runAs: a scheduled delivery has no inbound request, so the tenant identity must come
                // from the row itself — otherwise the audit entry would land without an organization.
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> auditClient.record(toReq(e)));
            }
        };
    }

    /**
     * Queue one audit event in the caller's transaction. Never throws: an audit problem must not fail
     * the marks entry a teacher just made — the outbox is what makes that safe, because an undelivered
     * row is retried rather than dropped.
     */
    public void record(String action, String entityType, String entityRef, String details) {
        if (action == null) return;
        AuditOutbox o = new AuditOutbox();
        o.setAction(action);
        o.setEntityType(entityType);
        o.setEntityRef(entityRef);
        o.setDetails(details);
        o.setEventKey(UUID.randomUUID().toString());   // one per event → audit-service dedups a retry
        o.setOccurredAt(LocalDateTime.now());
        o.setStatus("PENDING");
        o.setAttempts(0);
        o.setOrganizationId(CurrentUser.organizationId());
        o.setUserId(CurrentUser.userId());
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        final Long id = repo.save(o).getId();

        events.publishEvent(new AuditEnqueued(id));
    }

    /** Deliver right after the enqueuing transaction commits; runs inline if there was no transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(AuditEnqueued e) {
        relay.deliver(channel, e.id());
    }

    /** Retry relay — re-drives undelivered events (audit-service down, a timeout, a restart mid-delivery). */
    @Scheduled(fixedDelayString = "${audit.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private AuditEventRequest toReq(AuditOutbox o) {
        return AuditEventRequest.builder()
                .sourceService(SOURCE)
                .action(o.getAction())
                .entityType(o.getEntityType())
                .entityRef(o.getEntityRef())
                .details(o.getDetails())
                .eventKey(o.getEventKey())
                .occurredAt(o.getOccurredAt())
                .build();
    }
}
