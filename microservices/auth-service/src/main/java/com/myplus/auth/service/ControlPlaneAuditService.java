package com.myplus.auth.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.myplus.auth.entity.AuditOutbox;
import com.myplus.auth.repository.AuditOutboxRepository;
import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.audit.AuditActorType;
import com.myplus.common.audit.AuditEmitter;
import com.myplus.common.audit.AuditOutboxStore;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.security.CurrentUser;

/**
 * E4 — the control plane's record of its own decisions.
 *
 * <h3>The one thing this class exists to get right</h3>
 * <b>Which tenant the event belongs to.</b> Every other producer in the platform records a tenant's act upon
 * itself, so the question never arises. Here a platform operator acts upon a customer, and the two orgs are
 * different — so {@code subjectOrgId} is passed explicitly on every call and is never allowed to default.
 *
 * <p>Get it wrong and the event is filed under the operator: invisible to the customer, mixed in with every
 * other tenant's activity, and <b>unfixable</b>, because {@code audit_event} is append-only by design and by
 * constraint. This is the ONB-3 lesson one layer up — there, reading the operator's own org produced a wrong
 * NUMBER on a preview screen; here it would produce a permanent wrong RECORD.
 *
 * <h3>Actor type is stated where the actor is known, derived where it is not</h3>
 * {@link AuditEmitter} derives it from whether the actor's org matches the subject's, which is correct and is
 * the safety net. {@link #operatorAction} and {@link #tenantAction} state it anyway, because a reader of
 * {@code EntitlementService} should see what the record will say without following it here. {@link #shapeAction}
 * is the deliberate exception: one code path serves both an operator and a tenant, so stating it would mean
 * stating it twice and the second one would eventually be wrong.
 */
@Service
public class ControlPlaneAuditService extends AuditEmitter<AuditOutbox> {

    private static final String SOURCE = "auth";

    // Event families. Grant and revoke are SEPARATE actions rather than one ENTITLEMENT_CHANGE carrying a
    // status, because "show me everything we withdrew this quarter" is the question that actually gets asked,
    // and it should not require parsing after_value.
    public static final String ENTITLEMENT_GRANT = "ENTITLEMENT_GRANT";
    public static final String ENTITLEMENT_REVOKE = "ENTITLEMENT_REVOKE";
    public static final String PLAN_CHANGE = "PLAN_CHANGE";
    public static final String STATUS_CHANGE = "STATUS_CHANGE";
    public static final String SHAPE_CHANGE = "SHAPE_CHANGE";
    public static final String CAPABILITY_TOGGLE = "CAPABILITY_TOGGLE";

    public static final String ENTITY_CAPABILITY = "CAPABILITY";
    public static final String ENTITY_ORGANIZATION = "ORGANIZATION";

    public ControlPlaneAuditService(AuditOutboxRepository repo, OutboxRelay relay,
                                    ApplicationEventPublisher events,
                                    ObjectProvider<AuditClient> auditClient) {
        super(SOURCE, new AuditOutboxStore<AuditOutbox>() {
            public AuditOutbox newRow() { return new AuditOutbox(); }
            public Optional<AuditOutbox> find(Long id) { return repo.findById(id); }
            public List<AuditOutbox> pending() { return repo.findTop100ByStatusOrderByIdAsc("PENDING"); }
            public AuditOutbox save(AuditOutbox e) { return repo.save(e); }
        }, relay, events, auditClient);
    }

    /**
     * An operator's act upon a customer tenant. Runs in the caller's transaction — call it beside the write,
     * never after a commit.
     *
     * @param subjectOrgId the tenant the change is ABOUT — never the operator's own
     * @param actorUserId  read from the validated token by the controller; a body-supplied actor is not an actor
     */
    public void operatorAction(String action, String entityType, String entityRef, Long subjectOrgId,
                               String before, String after, String reason, Long actorUserId, String details) {
        record(AuditRecord.builder()
                .action(action)
                .entityType(entityType)
                .entityRef(entityRef)
                .subjectOrgId(subjectOrgId)
                .beforeValue(before)
                .afterValue(after)
                .reason(reason)
                .details(details)
                .actorUserId(actorUserId)
                // The operator's OWN org, read from their request — which is exactly what must NOT be used as
                // the subject. Recording it here is what lets a customer see that the change came from outside.
                .actorOrgId(CurrentUser.organizationId())
                .actorType(AuditActorType.PLATFORM_OPERATOR)
                .build());
    }

    /**
     * A business-type change — the one event whose actor could be either an operator or the tenant itself.
     *
     * <p>{@code applyShape} serves both doors, so the actor type is DERIVED here rather than stated: the
     * emitter compares the actor's organization with the subject's, which is true by construction on both
     * paths. Stating it would mean stating it in two places, and the second would eventually be wrong.
     *
     * @param historyRef the {@code org_shape_history} row id — the trail points at the memento (ruling D-3)
     *                   rather than repeating what it holds
     */
    public void shapeAction(String historyRef, Long subjectOrgId, String before, String after,
                            String reason, Long actorUserId, String details) {
        record(AuditRecord.builder()
                .action(SHAPE_CHANGE)
                .entityType(ENTITY_ORGANIZATION)
                .entityRef(historyRef)
                .subjectOrgId(subjectOrgId)
                .beforeValue(before)
                .afterValue(after)
                .reason(reason)
                .details(details)
                .actorUserId(actorUserId)
                .actorOrgId(CurrentUser.organizationId())
                .build());
    }

    /**
     * A tenant acting on itself — today, an owner toggling a capability on their Configuration screen.
     *
     * <p>Subject and actor org are both the caller's, so every field defaults correctly and the emitter derives
     * {@link AuditActorType#MEMBER}. Stated as a separate method anyway: the two cases read differently at the
     * call site, and a single method taking a nullable subject is how the wrong one gets passed.
     */
    public void tenantAction(String action, String entityType, String entityRef,
                             String before, String after, String details) {
        record(AuditRecord.builder()
                .action(action)
                .entityType(entityType)
                .entityRef(entityRef)
                .beforeValue(before)
                .afterValue(after)
                .details(details)
                .actorType(AuditActorType.MEMBER)
                .build());
    }
}
