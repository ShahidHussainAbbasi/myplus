package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.audit.AuditActorType;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.marketplace.entity.AuditOutbox;
import com.myplus.marketplace.repository.AuditOutboxRepository;

/**
 * MKT-1f (G-16, R22.4) — marketplace-service's audit producer.
 *
 * <p>Every row is filed under the SELLER whose order it is about (the subject), so the seller reads in its own
 * Activity trail what MaxTheService did on its orders. The actor type is always STATED, never derived: a customer
 * acting on a public route has no org, and the emitter's derivation ("actor org ≠ subject org") would file the
 * customer's action as a platform operator's — the exact misattribution the actor axis exists to prevent.
 *
 * <p>Inherits the emitter's ordering: the row is written in the caller's transaction (a refused or rolled-back
 * action records nothing) and delivered after commit (a down audit-service never blocks the action).
 */
@Service
public class MarketplaceAuditService extends AuditEmitter<AuditOutbox> {

    private static final String SOURCE = "marketplace";

    public static final String CASE_OPENED = "MKT_CASE_OPENED";
    public static final String CASE_TASKED = "MKT_CASE_TASKED";
    public static final String CASE_REPLIED = "MKT_CASE_REPLIED";
    public static final String CASE_RESOLVED = "MKT_CASE_RESOLVED";
    public static final String RETURN_OPENED = "MKT_RETURN_OPENED";
    public static final String RETURN_DECIDED = "MKT_RETURN_DECIDED";
    public static final String RETURN_RECEIVED = "MKT_RETURN_RECEIVED";
    public static final String RETURN_REFUNDED = "MKT_RETURN_REFUNDED";

    public static final String ENTITY_CASE = "MKT_SUPPORT_CASE";
    public static final String ENTITY_RETURN = "MKT_RETURN";

    /** Who acted, as the trail must say it. */
    public enum Actor {
        CUSTOMER(AuditActorType.SYSTEM), OPERATOR(AuditActorType.PLATFORM_OPERATOR), SELLER(AuditActorType.MEMBER),
        SYSTEM(AuditActorType.SYSTEM);

        final AuditActorType type;

        Actor(AuditActorType type) { this.type = type; }
    }

    public MarketplaceAuditService(AuditOutboxRepository repo, OutboxRelay relay, ApplicationEventPublisher events,
                                   ObjectProvider<AuditClient> auditClient) {
        super(SOURCE, new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    /** One marketplace event about {@code sellerOrg}'s order. {@code before}/{@code after} are short states. */
    public void event(String action, String entityType, String ref, Long sellerOrg, Actor actor, String before,
                      String after, BigDecimal amount, String details) {
        record(AuditRecord.builder()
                .action(action)
                .entityType(entityType)
                .entityRef(ref)
                .subjectOrgId(sellerOrg)
                .actorType(actor.type)
                .beforeValue(before)
                .afterValue(after)
                .amount(amount)
                .details(actor == Actor.CUSTOMER ? "by the customer" + (details == null ? "" : " — " + details) : details)
                .build());
    }
}
