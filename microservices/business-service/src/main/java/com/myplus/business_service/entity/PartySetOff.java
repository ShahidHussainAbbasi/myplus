package com.myplus.business_service.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * DR-4 — one set-off: what a partner owed us (as our customer) cleared against what we owed them (as our supplier),
 * by agreement. Columns mirror V72__party_setoff.sql exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "party_setoff")
@Getter @Setter
public class PartySetOff {

    public static final String POSTED = "POSTED";
    public static final String REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "setoff_no", nullable = false, length = 20)
    private String setOffNo;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "vender_id", nullable = false)
    private Long venderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "receipt_no", length = 40)
    private String receiptNo;

    @Column(name = "voucher_no", length = 40)
    private String voucherNo;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "reversed_by")
    private Long reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    @Column(name = "reversal_key", length = 80)
    private String reversalKey;
}
