package com.myplus.catalog.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.catalog.entity.AuditOutbox;
import com.myplus.catalog.repository.AuditOutboxRepository;
import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.audit.AuditActorType;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;

/**
 * E5 — catalog-service's audit producer.
 *
 * <p>Exists for one thing: {@code clear-tracking-flags}, the platform's only write into a customer's own
 * records. Everything else catalog does is a tenant acting on itself and is already visible to that tenant on
 * its own screens; this is the one action taken by somebody outside the business, which is precisely what the
 * actor axis was built for.
 */
@Service
public class CatalogAuditService extends AuditEmitter<AuditOutbox> {

    private static final String SOURCE = "catalog";

    public static final String POLICY_CLEARED = "CATALOG_POLICY_CLEARED";
    public static final String ENTITY_POLICY = "PRODUCT_POLICY";

    public CatalogAuditService(AuditOutboxRepository repo, OutboxRelay relay, ApplicationEventPublisher events,
                               ObjectProvider<AuditClient> auditClient) {
        super(SOURCE, new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    /**
     * Record a bulk policy clear against the tenant whose products changed.
     *
     * <p>The subject is the CUSTOMER, never the operator — so the record lands in the customer's own trail,
     * where E5's Platform access card and E4's Activity panel both read it. Actor type is stated rather than
     * left to the emitter's derivation because this method has exactly one caller and one meaning.
     */
    public void policyCleared(Long subjectOrgId, String capability, int products, String reason) {
        record(AuditRecord.builder()
                .action(POLICY_CLEARED)
                .entityType(ENTITY_POLICY)
                .entityRef(capability)
                .subjectOrgId(subjectOrgId)
                .beforeValue("required")
                .afterValue("cleared")
                .reason(reason)
                .details(products + " products")
                .actorOrgId(CurrentUser.organizationId())
                .actorType(AuditActorType.PLATFORM_OPERATOR)
                .build());
    }
}
