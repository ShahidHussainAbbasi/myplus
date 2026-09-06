package com.myplus.education.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.education.entity.AuditOutbox;
import com.myplus.education.repository.AuditOutboxRepository;

/**
 * education-service's producer for the standalone audit-service.
 *
 * <h3>D-7 closed here</h3>
 * This class used to carry its own copy of the machinery — the {@code OutboxDelivery} channel, the
 * {@code runAs} delivery, the {@code @Scheduled} relay, the request mapping — which made it the THIRD
 * implementation of one thing. E4 extracted {@link AuditEmitter} at the second consumer and deliberately
 * left this one alone, because its table was a different shape and narrowing a live column is a data
 * decision. {@code V30} settled that; this now shares the one implementation.
 *
 * <p>Behaviour is unchanged for its ten callers: {@link #record(String, String, String, String)} keeps the
 * signature they use, and the emitter's defaults resolve identity to exactly what this class computed by
 * hand. What it gains is the ability to record a REASON and an actor type — without which education could
 * not record a D-6 re-drive at all.
 */
@Service
public class EduAuditService extends AuditEmitter<AuditOutbox> {

    private static final String SOURCE = "education";

    public EduAuditService(AuditOutboxRepository repo, OutboxRelay relay, ApplicationEventPublisher events,
                           ObjectProvider<AuditClient> auditClient) {
        super(SOURCE, new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    /**
     * Record one teaching or portal event. Atomic with the caller's transaction, delivered after it commits.
     *
     * <p>Kept as a positional method rather than exposing {@link AuditRecord} to the ten call sites: every
     * one of them describes the same shape — a thing that happened to a named record — and a builder would
     * add ceremony to a call that is already unambiguous. It also means this migration changed none of them.
     */
    public void record(String action, String entityType, String entityRef, String details) {
        record(AuditRecord.builder()
                .action(action)
                .entityType(entityType)
                .entityRef(entityRef)
                .details(details)
                .build());
    }
}
