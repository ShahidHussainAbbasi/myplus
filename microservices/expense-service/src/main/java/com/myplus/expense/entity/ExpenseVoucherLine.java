package com.myplus.expense.entity;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One cost on a voucher. Account code and category name are SNAPSHOTS: re-mapping a category never rewrites history. */
@Entity
@Table(name = "expense_voucher_line")
@Getter @Setter
public class ExpenseVoucherLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private ExpenseVoucher voucher;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "account_code", nullable = false, length = 16)
    private String accountCode;

    @Column(name = "category_name", length = 120)
    private String categoryName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** EX-2b — what this line was FOR (SCHOOL, VEHICLE, LAND), confirmed by the owning module; null = untagged. */
    @Column(name = "tag_type", length = 16)
    private String tagType;

    @Column(name = "tag_id")
    private Long tagId;

    /** Snapshot of the module's label at save: renaming the record later never rewrites this expense. */
    @Column(name = "tag_label", length = 160)
    private String tagLabel;
}
