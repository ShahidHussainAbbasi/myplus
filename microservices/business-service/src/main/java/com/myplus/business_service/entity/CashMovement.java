package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A cash-drawer movement within a shift (POS day-close, slice 39). Org-scoped, linked to a shift. */
@Entity
@Table(name = "cash_movement", indexes = @Index(name = "idx_cashmove_shift", columnList = "shift_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;              // multi-location: the store whose drawer this movement hit

    @Column(name = "shift_id")
    private Long shiftId;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    private String reason;

    @Column(name = "dated")
    private LocalDateTime dated;

    /** EX-3 — the expense category of a PAY_OUT while Expense management is on; null otherwise. */
    @Column(name = "category_id")
    private Long categoryId;

    /** EX-3 — one per form submission; UNIQUE per org, so a double click replays instead of paying out twice. */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** EX-3 — the EXP- number expense-service gave this pay-out, stamped when it is delivered. */
    @Column(name = "expense_voucher_no", length = 20)
    private String expenseVoucherNo;

    @PrePersist
    void onCreate() { if (dated == null) dated = LocalDateTime.now(); }
}
