package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit #4: a queued GL posting event (transactional outbox). Written in the same TX as the sale/purchase/return/
 * edit so the event can't be lost; delivered to finance-service by GlOutboxService (afterCommit + @Scheduled relay).
 * Columns mirror commerce-contracts PostingEventRequest. Tenant-scoped (org/user carried for the relay's runAs).
 */
@Data
@Entity
@Table(name = "gl_outbox", indexes = { @Index(name = "idx_outbox_pending", columnList = "status,id") })
public class GlOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;   // SALE | PURCHASE | SALE_RETURN | PURCHASE_RETURN

    // Audit #5: a stable per-event UUID so finance dedups a duplicate outbox delivery (closes the #4 dup-journal window).
    @Column(name = "event_key", length = 64)
    private String eventKey;

    private String ref;

    @Column(name = "grand_total", precision = 19, scale = 2)
    private BigDecimal grandTotal;
    @Column(name = "sub_total", precision = 19, scale = 2)
    private BigDecimal subTotal;
    @Column(name = "tax_total", precision = 19, scale = 2)
    private BigDecimal taxTotal;
    @Column(precision = 19, scale = 2)
    private BigDecimal cost;
    @Column(name = "paid_amount", precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(length = 30)
    private String method;

    @Column(nullable = false, length = 20)
    private String status;      // PENDING | POSTED | FAILED

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "organization_id")
    private Long organizationId;
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
