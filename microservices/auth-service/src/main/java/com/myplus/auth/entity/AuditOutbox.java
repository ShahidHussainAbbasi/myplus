package com.myplus.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.myplus.common.audit.AbstractAuditOutbox;

/**
 * E4 — auth-service's queued control-plane audit events.
 *
 * <p>Written in the SAME transaction as the entitlement / plan / status / shape / capability change it
 * records, so the two cannot disagree: a refused change (E1's ceiling throws before the upsert) takes this row
 * with it, and a committed change always has its record. Delivered to audit-service after commit.
 *
 * <p>Columns live on {@link AbstractAuditOutbox}. The table is this service's own, per the schema-ownership
 * standard — {@code V10__audit_outbox.sql}.
 */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
