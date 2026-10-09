package com.myplus.expense.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * EX-7b — one advance given to a member, or taken back from them (V10). finance records the money (an EMPLOYEE payment
 * with purpose ADVANCE); this row carries the duplicate guarantee (UNIQUE organization_id + idempotency_key) and the
 * {@link #reference} a lost answer is looked up by, exactly as a bill payment does.
 */
@Entity
@Table(name = "expense_advance_movement")
@Getter @Setter
public class ExpenseAdvanceMovement {

    public static final String GIVE = "GIVE", TAKE_BACK = "TAKE_BACK";
    public static final String PENDING = "PENDING", RECORDED = "RECORDED", FAILED = "FAILED", REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "member_name", length = 160)
    private String memberName;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reference", length = 40)
    private String reference;

    @Column(name = "receipt_no", length = 40)
    private String receiptNo;

    @Column(name = "finance_payment_id")
    private Long financePaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isGive() { return GIVE.equals(kind); }
}
