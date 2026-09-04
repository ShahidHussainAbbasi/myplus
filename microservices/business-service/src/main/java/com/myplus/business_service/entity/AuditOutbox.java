package com.myplus.business_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.myplus.common.audit.AbstractAuditOutbox;

/**
 * Audit #6: business-service's queued audit events (transactional outbox). Written in the SAME transaction as
 * the money/stock change so an event can neither be lost nor survive a rollback; delivered to audit-service
 * after commit by {@code AuditService}.
 *
 * <p>E4 moved the columns to {@link AbstractAuditOutbox} — a {@code @MappedSuperclass}, so this service still
 * owns the {@code audit_outbox} TABLE and its migrations, and only the column set and the delivery behaviour
 * are shared with the second producer (auth-service). The extraction happened at the second consumer rather
 * than the third, per the standing rule.
 */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
