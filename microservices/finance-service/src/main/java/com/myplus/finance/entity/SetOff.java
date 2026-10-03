package com.myplus.finance.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** DR-4 — one set-off and the payments it recorded. Columns mirror V10__setoff.sql ({@code ddl-auto=validate}). */
@Entity
@Table(name = "setoff")
@Getter @Setter
public class SetOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "setoff_no", nullable = false, length = 20)
    private String setOffNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "receipt_payment_id", nullable = false)
    private Long receiptPaymentId;

    @Column(name = "disbursement_payment_id", nullable = false)
    private Long disbursementPaymentId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "reversal_key", length = 80)
    private String reversalKey;

    @Column(name = "reversal_reason", length = 255)
    private String reversalReason;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_receipt_id")
    private Long reversalReceiptId;

    @Column(name = "reversal_disbursement_id")
    private Long reversalDisbursementId;
}
