package com.myplus.business_service.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Data;

/**
 * Audit #5: one row per completed (or in-flight) money operation, keyed by (org, operation, client key). The unique
 * index is the race backstop; {@code resultRef} holds the op's identifier (voucher no / purchaseId) so a replay
 * returns the same result instead of re-charging. See {@code IdempotencyService}.
 */
@Data
@Entity
@Table(name = "idempotency_record", uniqueConstraints = {
        @UniqueConstraint(name = "uq_idem_org_op_key", columnNames = {"organization_id", "operation", "idem_key"}) })
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    @Column(name = "idem_key", nullable = false, length = 191)
    private String idemKey;

    @Column(name = "result_ref", length = 191)
    private String resultRef;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
