package com.myplus.clinical.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.clinical.entity.AuditOutbox;
import com.myplus.clinical.repository.AuditOutboxRepo;
import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;

/**
 * Every patient registration, change, link and VIEW goes to the platform's immutable audit trail — for patient
 * data a read is recorded, not only a write (programme rule 2). Captured in the request's transaction, delivered
 * after commit by the shared outbox: a slow audit-service never stops the front desk. S5 builds the owner's
 * Access log screen on these rows.
 *
 * <p>Details carry the MRN and the action, NEVER the name, phone or CNIC — the audit trail is read more widely
 * than the register and must not become a second copy of it.
 */
@Service
public class ClinicAuditService extends AuditEmitter<AuditOutbox> {

    public static final String ENTITY = "PATIENT";

    public ClinicAuditService(AuditOutboxRepo repo, OutboxRelay relay, ApplicationEventPublisher events,
                              ObjectProvider<AuditClient> auditClient) {
        super("clinic", new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    public void patient(String action, String mrn, String details) {
        record(AuditRecord.builder().action(action).entityType(ENTITY).entityRef(mrn).details(details).build());
    }
}
