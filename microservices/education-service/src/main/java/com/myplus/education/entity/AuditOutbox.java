package com.myplus.education.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.myplus.common.audit.AbstractAuditOutbox;

/**
 * education-service's queued audit events.
 *
 * <p>D-7 closed: the columns now live on {@link AbstractAuditOutbox}, shared with business, auth and catalog.
 * This service was left out when common-audit was extracted at E4 because its table was a different shape —
 * a decision recorded as debt rather than quietly taken — and {@code V30} reconciled it once the data proved
 * nothing would be truncated.
 *
 * <p>The table is still this service's own, per the schema-ownership standard. What is shared is the column
 * set and the delivery behaviour, not the table.
 */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
