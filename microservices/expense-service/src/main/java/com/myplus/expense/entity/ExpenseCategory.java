package com.myplus.expense.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** What a business spends ON. The name is the owner's word; {@code accountCode} decides the ledger account. */
@Entity
@Table(name = "expense_category")
@Getter @Setter
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "account_code", nullable = false, length = 16)
    private String accountCode;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
