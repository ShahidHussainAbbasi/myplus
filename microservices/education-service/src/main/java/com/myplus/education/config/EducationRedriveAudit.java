package com.myplus.education.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.myplus.common.audit.AuditActorType;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.outbox.OutboxRedriveAudit;
import com.myplus.common.security.CurrentUser;

/**
 * D-6 — records a re-drive of this service's outboxes in the audit trail.
 *
 * <h3>Closing a hole in D-6's own argument</h3>
 * The slice shipped with this bean on auth and catalog only, so a re-drive on the two services that hold the
 * GENERAL-LEDGER outboxes — the ones the whole finding was about — worked and was <b>not recorded</b>. An
 * accountability feature with an unrecorded control is the shape of problem it exists to prevent.
 *
 * <p>An SPI rather than a call into common-audit, because {@code common-audit} is built ON
 * {@code common-outbox}: a dependency the other way would be a cycle. The module that owns the re-drive
 * publishes; the module that owns the trail listens.
 *
 * <p>Recorded as {@code PLATFORM_OPERATOR}: somebody outside the tenant pushed events into their records,
 * which is exactly the distinction E4's actor axis was built to carry.
 */
@Configuration
public class EducationRedriveAudit {

    @Bean
    public OutboxRedriveAudit outboxRedriveAudit(com.myplus.education.service.EduAuditService producer) {
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
