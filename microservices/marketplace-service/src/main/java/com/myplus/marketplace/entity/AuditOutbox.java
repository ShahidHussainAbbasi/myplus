package com.myplus.marketplace.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.myplus.common.audit.AbstractAuditOutbox;

/**
 * MKT-1f (G-16) — marketplace-service's queued audit events. Columns live on {@link AbstractAuditOutbox}; the table
 * is this service's own ({@code V30}), per the schema-ownership standard.
 */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
