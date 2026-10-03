package com.myplus.expense.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * FP-3 — one payment against a bill (V4). finance-service records the money; this row is expense-service's side of
 * it and carries the duplicate guarantee: UNIQUE (organization_id, idempotency_key), and {@link #reference} sent to
 * finance so a payment whose outcome was lost (a timeout) is FOUND there before anything is sent again.
 */
@Entity
@Table(name = "expense_bill_payment")
@Getter @Setter
public class ExpenseBillPayment {

    public static final String PENDING = "PENDING", RECORDED = "RECORDED", FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reference", length = 40)
    private String reference;

    @Column(name = "receipt_no", length = 20)
    private String receiptNo;

    @Column(name = "finance_payment_id")
    private Long financePaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void setLastError(String e) {
        this.lastError = e == null ? null : (e.length() > 500 ? e.substring(0, 500) : e);
    }
}
