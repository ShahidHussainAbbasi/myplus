package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.business_service.entity.AuditOutbox;
import com.myplus.business_service.repository.AuditOutboxRepo;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.commerce.contracts.client.AuditClient;

/**
 * Audit #6: business-service's producer for the standalone audit-service.
 *
 * <p>E4 moved the machinery — enqueue in the caller's transaction, deliver {@code AFTER_COMMIT}, re-drive with
 * the shared {@link OutboxRelay} — into {@link AuditEmitter}, when auth-service became the second producer.
 * What is left here is what is genuinely business-service's: the table it writes to, and a {@code record(...)}
 * signature shaped for money and stock events so the eleven call sites read the way they always did.
 *
 * <p>Behaviour is unchanged. Identity still comes from the authenticated request, and the emitter's defaults
 * resolve to exactly what this class used to compute by hand ({@code RequestUtil.getCurrentUser()} is
 * {@code CurrentUser.get()}), so a trading event is still filed under the tenant that made it, as a
 * {@code MEMBER} — which is what every row written before E4 is.
 */
@Service
public class AuditService extends AuditEmitter<AuditOutbox> {

    private static final String SOURCE = "business";

    public AuditService(AuditOutboxRepo repo, OutboxRelay relay, ApplicationEventPublisher events,
                        ObjectProvider<AuditClient> auditClient) {
        super(SOURCE, new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    /**
     * Record one money/stock event: atomic with the caller's transaction, delivered after it commits.
     *
     * <p>Kept as a positional method rather than exposing {@link AuditRecord} to the eleven call sites, because
     * every one of them describes the same shape — an amount and a document — and a builder would add ceremony
     * to a call that is already unambiguous.
     */
    public void record(String action, String entityType, String entityRef, BigDecimal amount, String details) {
        record(AuditRecord.builder()
                .action(action)
                .entityType(entityType)
                .entityRef(entityRef)
                .amount(amount)
                .details(details)
                .build());
    }
}
