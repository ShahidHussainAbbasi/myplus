package com.myplus.audit.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.*;

/**
 * Ingestion payload from a producer.
 *
 * <h3>The trust boundary, and what E4 changed about it</h3>
 * {@code organizationId} and {@code userId} are still taken from the AUTHENTICATED REQUEST and never from this
 * body — a producer impersonates the tenant through the gateway ({@code GatewayIdentityForwarding.runAs}), so
 * a payload can never move a row to a different tenant.
 *
 * <p>The E4 fields below are producer ASSERTIONS, trusted on the strength of {@code X-Internal-Secret}. The
 * distinction that matters: they can DESCRIBE the actor, but they cannot re-file the record.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditRecordRequest {
    private String sourceService;
    private String action;
    private String entityType;
    private String entityRef;
    private BigDecimal amount;
    private String details;
    private String eventKey;
    private LocalDateTime occurredAt;

    // ── E4: the control-plane fields ──────────────────────────────────────────────────────────────
    /** The actor's own organization. Equal to the subject org for an insider; different for the platform. */
    private Long actorOrgId;
    /** MEMBER | PLATFORM_OPERATOR | SYSTEM — see AuditActorType. Never a role name. */
    private String actorType;
    /** Stamped so the trail survives the person leaving. */
    private String actorEmail;
    /** Mandatory on every control-plane write since E2. */
    private String reason;
    private String beforeValue;
    private String afterValue;
}
