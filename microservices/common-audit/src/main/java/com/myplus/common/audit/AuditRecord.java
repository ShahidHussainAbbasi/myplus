package com.myplus.common.audit;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

/**
 * E4 — one thing worth recording, as the caller describes it.
 *
 * <p>A value object rather than a nine-argument method, because the two producers fill in different halves:
 * business-service sets {@code amount} and never {@code beforeValue}; auth-service is the reverse. A positional
 * signature covering both would be mostly nulls at every call site, and the field a caller forgot would be the
 * one nobody noticed missing until an incident.
 *
 * <h3>Identity is NOT here</h3>
 * {@code subjectOrgId}, {@code actorUserId}, {@code actorOrgId} and {@code actorEmail} are on the builder
 * because the two producers answer them differently — a shop records its own act, an operator records an act
 * upon somebody else. But {@link AuditEmitter#record} defaults every one of them to the authenticated caller,
 * so the ordinary case stays a four-field call and only the unusual one spells itself out.
 */
@Getter
@Builder
public class AuditRecord {

    /** SALE · ENTITLEMENT_REVOKE · … Fits {@code VARCHAR(32)}; see AuditIngestService for the families. */
    private final String action;

    private final String entityType;
    private final String entityRef;
    private final BigDecimal amount;
    private final String details;

    /** Mandatory on control-plane events; absent on trading events, which explain themselves. */
    private final String reason;

    private final String beforeValue;
    private final String afterValue;

    /**
     * The tenant this event is ABOUT. Null means the caller's own — the ordinary case.
     *
     * <p>Set explicitly only when acting on somebody else's tenant, which today means a platform operator.
     * Getting this wrong writes the record into the wrong customer's history, permanently.
     */
    private final Long subjectOrgId;

    /** Null means the authenticated caller. */
    private final Long actorUserId;
    private final Long actorOrgId;
    private final String actorEmail;

    /** Null defaults to {@link AuditActorType#MEMBER} — what every pre-E4 producer is. */
    private final AuditActorType actorType;
}
