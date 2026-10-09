package com.myplus.expense.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * EX-7b — what one member holds of the business's money (1300 Employee Advance), net of what they have spent on approved
 * claims or handed back (V10). The row every advance change locks, so the same advance is never spent twice.
 */
@Entity
@Table(name = "expense_advance_balance")
@Getter @Setter
public class ExpenseAdvanceBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "member_name", length = 160)
    private String memberName;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Reserve (or spend) part of the balance. Never below zero: that would be money nobody holds. */
    public void take(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Enter an amount greater than zero.");
        if (amount.compareTo(balance) > 0)
            throw new IllegalStateException("That is more than this member holds as an advance (" + balance + ").");
        balance = balance.subtract(amount);
    }

    public void add(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Enter an amount greater than zero.");
        balance = balance.add(amount);
    }
}
