package com.myplus.market.entity;

import com.myplus.common.audit.AbstractAuditOutbox;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** This service's local outbox for the shared audit trail (the expense-service pattern). */
@Entity
@Table(name = "audit_outbox", indexes = { @Index(name = "idx_audit_outbox_pending", columnList = "status,id") })
public class AuditOutbox extends AbstractAuditOutbox {
}
