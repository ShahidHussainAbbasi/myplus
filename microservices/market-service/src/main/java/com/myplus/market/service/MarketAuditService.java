package com.myplus.market.service;

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
import com.myplus.market.entity.AuditOutbox;
import com.myplus.market.repository.AuditOutboxRepo;

/**
 * Every marketplace action goes to the platform's immutable audit trail (source design §22: "every marketplace
 * action is audited"). Captured in the writing transaction, delivered after commit by the shared outbox — a slow
 * audit-service never fails a marketplace action.
 *
 * <p>An operator acting on a seller passes the seller's org as the subject, so the event lands in THAT seller's
 * history, and the emitter derives {@code PLATFORM_OPERATOR} from the org mismatch.
 */
@Service
public class MarketAuditService extends AuditEmitter<AuditOutbox> {

    public MarketAuditService(AuditOutboxRepo repo, OutboxRelay relay, ApplicationEventPublisher events,
                              ObjectProvider<AuditClient> auditClient) {
        super("market", new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    public void record(String action, String entityType, String entityRef, Long subjectOrgId,
                       String before, String after, String details, String reason) {
        record(AuditRecord.builder()
                .action(action).entityType(entityType).entityRef(entityRef)
                .subjectOrgId(subjectOrgId)
                .beforeValue(before).afterValue(after)
                .details(details).reason(reason)
                .build());
    }
}
