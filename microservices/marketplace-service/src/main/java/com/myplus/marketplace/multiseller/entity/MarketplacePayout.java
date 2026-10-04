package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1g — a manual payout to one seller (V31, source §16, R22.3): REQUESTED by one operator, APPROVED by a different
 * one (four eyes), PAID with the bank's reference. The idempotency key makes a repeated request the same payout.
 */
@Entity
@Table(name = "mkt_payout")
@Getter
@Setter
@NoArgsConstructor
public class MarketplacePayout {

    public static final String REQUESTED = "REQUESTED";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_no", nullable = false, length = 32)
    private String payoutNo;

    /** The seller paid. */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "approved_amount", precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "bank_reference", length = 80)
    private String bankReference;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "paid_by_user_id")
    private Long paidByUserId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }
}
