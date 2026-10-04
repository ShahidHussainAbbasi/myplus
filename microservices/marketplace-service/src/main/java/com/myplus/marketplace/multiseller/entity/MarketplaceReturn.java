package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1f — the return of one order line (V30). The cost bearer is resolved WHEN OPENED from the line's party snapshot
 * (R13.1, R13.3) and never re-read; a later change of policy or custodian cannot move an old return's cost.
 */
@Entity
@Table(name = "mkt_return")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceReturn {

    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    /** The seller has the item; the credit note and refund follow. Claimed first so a retry cannot double them. */
    public static final String RECEIVED = "RECEIVED";
    public static final String REFUNDED = "REFUNDED";

    public static final String CARD = "CARD";
    public static final String CASH_AT_PICKUP = "CASH_AT_PICKUP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_no", nullable = false, length = 32)
    private String returnNo;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "mkt_order_id", nullable = false)
    private Long mktOrderId;

    @Column(name = "order_line_id", nullable = false)
    private Long orderLineId;

    @Column(name = "seller_org_id", nullable = false)
    private Long sellerOrgId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "bearer_role", nullable = false, length = 16)
    private String bearerRole;

    @Column(name = "bearer_org_id")
    private Long bearerOrgId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "line_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineAmount;

    @Column(name = "deduction", nullable = false, precision = 19, scale = 2)
    private BigDecimal deduction;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_channel", length = 16)
    private String refundChannel;

    @Column(name = "credit_note_no", length = 200)
    private String creditNoteNo;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "received_by_user_id")
    private Long receivedByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
