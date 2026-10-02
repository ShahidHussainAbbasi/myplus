package com.myplus.expense.service;

import java.math.BigDecimal;
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
import com.myplus.expense.entity.AuditOutbox;
import com.myplus.expense.repository.AuditOutboxRepo;

/**
 * Every financial action on an expense goes to the platform's immutable audit trail (STANDARDS §22 of the
 * programme design): record, post, void, and category changes. Captured in the writing transaction, delivered
 * after commit by the shared outbox — a slow audit-service never fails an expense.
 */
@Service
public class ExpenseAuditService extends AuditEmitter<AuditOutbox> {

    public ExpenseAuditService(AuditOutboxRepo repo, OutboxRelay relay, ApplicationEventPublisher events,
                               ObjectProvider<AuditClient> auditClient) {
        super("expense", new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    public void record(String action, String entityType, String entityRef, BigDecimal amount, String details, String reason) {
        record(AuditRecord.builder()
                .action(action).entityType(entityType).entityRef(entityRef)
                .amount(amount).details(details).reason(reason)
                .build());
    }
}
