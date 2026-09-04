package com.myplus.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.myplus.common.audit.AuditActorType;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRedriveAudit;
import com.myplus.common.security.CurrentUser;

/**
 * D-6 — records a re-drive in the audit trail.
 *
 * <h3>An SPI, because the dependency only runs one way</h3>
 * {@code common-audit} is built ON {@code common-outbox}; a call the other way would be a cycle. So the
 * module that owns the re-drive publishes and the module that owns the trail listens — the same shape as
 * {@code OutboxDelivery}.
 *
 * <p>A re-drive is a CONTROL-PLANE action: somebody outside a tenant pushed events into their records. E4
 * built exactly the actor axis that says so, so this reuses it rather than inventing a second trail.
 */
@Configuration
public class AuthRedriveAudit {

    @Bean
    public OutboxRedriveAudit outboxRedriveAudit(com.myplus.auth.service.ControlPlaneAuditService producer) {
        return (table, count, reason) -> producer.record(AuditRecord.builder()
                .action("OUTBOX_REDRIVEN")
                .entityType("OUTBOX")
                .entityRef(table)
                .beforeValue("failed")
                .afterValue("queued")
                .details(count + " records")
                .reason(reason)
                .actorOrgId(CurrentUser.organizationId())
                .actorType(AuditActorType.PLATFORM_OPERATOR)
                .build());
    }
}
