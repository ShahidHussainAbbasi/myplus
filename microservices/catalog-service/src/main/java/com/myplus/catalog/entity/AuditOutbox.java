package com.myplus.catalog.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.myplus.common.audit.AbstractAuditOutbox;

/**
 * E5 — catalog-service's queued audit events.
 *
 * <p>Columns live on {@link AbstractAuditOutbox}; the table is this service's own ({@code V15__audit_outbox}),
 * per the schema-ownership standard. catalog is the first service to adopt {@code common-audit} without
 * having written its own copy of the producer first.
 */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
