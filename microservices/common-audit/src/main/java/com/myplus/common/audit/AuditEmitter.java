package com.myplus.common.audit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.commerce.contracts.dto.AuditEventRequest;
import com.myplus.common.outbox.OutboxDelivery;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * E4 — the shared audit PRODUCER: capture in the caller's transaction, deliver after it commits, re-drive what
 * did not land.
 *
 * <h3>Why this is a library and not a second copy</h3>
 * business-service was the first producer; auth-service is the second, and the standing rule is to extract
 * domain-free at the second consumer rather than the third. The part being shared is not convenience code —
 * it is <b>when a row is written relative to the commit that justifies it</b>, which is the one thing that
 * must not drift between two producers. Two copies would eventually disagree about it, and the disagreement
 * would look like a missing event rather than a bug.
 *
 * <h3>The two properties the ordering buys</h3>
 * <ul>
 *   <li><b>A refused or rolled-back operation records nothing.</b> {@link #record} inserts inside the CALLER'S
 *       transaction, so a later throw takes the outbox row with it. This is what makes E1's entitlement
 *       refusals invisible in the trail — and a trail containing changes that never happened is worse than no
 *       trail, because nothing downstream can tell which rows are real.</li>
 *   <li><b>A down audit-service never blocks the operation.</b> Delivery is {@code AFTER_COMMIT}; a failure
 *       leaves the row PENDING and {@link #flushPending} re-drives it. The business keeps working and the
 *       trail catches up.</li>
 * </ul>
 *
 * <h3>Identity: delivered AS the subject tenant</h3>
 * audit-service files a row under the org of the authenticated request, never the payload — so the producer
 * must impersonate the tenant the event is ABOUT via {@link GatewayIdentityForwarding#runAs}. For a shop
 * recording its own sale those are the same org. For a platform operator acting on a customer they are not,
 * and the difference is permanent: {@code audit_event} is append-only, so an event filed under the operator is
 * invisible to that customer forever.
 *
 * @param <E> the consumer's own outbox entity
 */
public abstract class AuditEmitter<E extends AbstractAuditOutbox> {

    /** Published once a row is enqueued; delivered after the caller's transaction commits. */
    public record AuditEnqueued(Long id) {}

    private final AuditOutboxStore<E> store;
    private final OutboxRelay relay;
    private final ApplicationEventPublisher events;

    /**
     * The shared audit-service client, resolved at delivery time.
     *
     * <h3>{@code ObjectProvider}, not {@code @Autowired(required = false)}</h3>
     * That annotation does nothing on a CONSTRUCTOR PARAMETER — it is honoured on fields and on the
     * constructor itself, not on an individual argument — so a service without the bean would have failed to
     * start rather than degrading, and one with it would have looked fine, hiding the mistake everywhere it
     * mattered least. {@code ObjectProvider} is the idiomatic "zero or one bean" injection.
     *
     * <p>Resolved lazily rather than in the constructor, so a client contributed by a later-initialising
     * configuration is still found.
     *
     * <p>Absent does <b>not</b> silently disable auditing — that would be the anti-pattern
     * {@code JpaSettingsStore}'s javadoc warns about, where a missing collaborator reads as protection.
     * {@link OutboxDelivery#available()} returns false, rows stay PENDING, and the relay delivers them when
     * the client appears. Nothing is lost; it is deferred, and visibly so.
     */
    private final ObjectProvider<AuditClient> auditClient;

    /** Stamped on every event so a reader can tell which service asserted it ("business", "auth", …). */
    private final String sourceService;

    private final OutboxDelivery<E> channel;

    protected AuditEmitter(String sourceService, AuditOutboxStore<E> store, OutboxRelay relay,
                           ApplicationEventPublisher events, ObjectProvider<AuditClient> auditClient) {
        this.sourceService = sourceService;
        this.store = store;
        this.relay = relay;
        this.events = events;
        this.auditClient = auditClient;
        this.channel = new OutboxDelivery<E>() {
            public String name() { return "Audit"; }
            public boolean available() { return AuditEmitter.this.auditClient.getIfAvailable() != null; }
            public Optional<E> find(Long id) { return AuditEmitter.this.store.find(id); }
            public List<E> pending() { return AuditEmitter.this.store.pending(); }
            public E save(E e) { return AuditEmitter.this.store.save(e); }
            public void send(E e) {
                // AS THE SUBJECT TENANT. e.getOrganizationId() is the org the event is about, which for a
                // control-plane event is the customer and not the operator who caused it.
                AuditClient client = AuditEmitter.this.auditClient.getIfAvailable();
                if (client == null) throw new IllegalStateException("audit-service client is not wired");
                GatewayIdentityForwarding.runAs(e.getUserId(), e.getOrganizationId(),
                        () -> client.record(toRequest(e)));
            }
        };
    }

    /**
     * Record one event. Runs inside whatever transaction the caller is already in — that is the whole design.
     *
     * <p>Every identity field defaults to the authenticated caller, so the ordinary "this tenant did this"
     * call stays short and only a cross-tenant act has to say so explicitly.
     */
    public void record(AuditRecord r) {
        if (r == null || r.getAction() == null) return;

        Long ownOrg = CurrentUser.organizationId();
        Long subject = r.getSubjectOrgId() != null ? r.getSubjectOrgId() : ownOrg;
        Long actorOrg = r.getActorOrgId() != null ? r.getActorOrgId() : ownOrg;

        E row = store.newRow();
        row.setAction(r.getAction());
        row.setEntityType(r.getEntityType());
        row.setEntityRef(r.getEntityRef());
        row.setAmount(r.getAmount());
        row.setDetails(trim(r.getDetails(), 500));
        row.setReason(trim(r.getReason(), 255));
        row.setBeforeValue(trim(r.getBeforeValue(), 64));
        row.setAfterValue(trim(r.getAfterValue(), 64));

        row.setOrganizationId(subject);
        row.setUserId(r.getActorUserId() != null ? r.getActorUserId() : CurrentUser.userId());
        row.setActorOrgId(actorOrg);
        row.setActorEmail(trim(r.getActorEmail() != null ? r.getActorEmail() : CurrentUser.email(), 160));
        /*
         * The actor type, DERIVED when the caller did not state one — and derived from the only thing that is
         * reliably true: whether the actor's org is the org this event is about. A producer that has to
         * remember to pass PLATFORM_OPERATOR will one day forget, and the forgotten case renders as though a
         * customer's own staff made the change.
         */
        row.setActorType((r.getActorType() != null
                ? r.getActorType()
                : (subject != null && subject.equals(actorOrg) ? AuditActorType.MEMBER
                                                               : AuditActorType.PLATFORM_OPERATOR)).code());

        row.setEventKey(UUID.randomUUID().toString());
        row.setOccurredAt(LocalDateTime.now());
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());

        Long id = store.save(row).getId();
        events.publishEvent(new AuditEnqueued(id));
    }

    /** Deliver as soon as the enqueuing transaction commits; inline when there was none. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEnqueued(AuditEnqueued e) {
        relay.deliver(channel, e.id());
    }

    /** Deliver one row (POSTED/FAILED are skipped) — exposed for tests and manual re-drives. */
    public void tryDeliver(Long id) {
        relay.deliver(channel, id);
    }

    /** The retry relay. The AFTER_COMMIT path is the normal one; this exists for when it did not work. */
    @Scheduled(fixedDelayString = "${audit.outbox.relay-delay-ms:30000}")
    public void flushPending() {
        relay.flush(channel);
    }

    private AuditEventRequest toRequest(E o) {
        return AuditEventRequest.builder()
                .sourceService(sourceService)
                .action(o.getAction())
                .entityType(o.getEntityType())
                .entityRef(o.getEntityRef())
                .amount(o.getAmount())
                .details(o.getDetails())
                .reason(o.getReason())
                .beforeValue(o.getBeforeValue())
                .afterValue(o.getAfterValue())
                .actorOrgId(o.getActorOrgId())
                .actorType(o.getActorType())
                .actorEmail(o.getActorEmail())
                .eventKey(o.getEventKey())
                .occurredAt(o.getOccurredAt())
                .build();
    }

    private static String trim(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }
}
