package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * F3 (GL): one side of a journal entry — a debit OR a credit against an account. Exactly one of {@code debit}/
 * {@code credit} is positive on a normal line. Trial balance / statements aggregate these by account.
 */
@Entity
@Table(name = "journal_lines", indexes = {
        @Index(name = "idx_jl_account", columnList = "account_id"),
        @Index(name = "idx_jl_entry", columnList = "entry_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id")
    private JournalEntry entry;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(precision = 19, scale = 2)
    private BigDecimal debit;

    @Column(precision = 19, scale = 2)
    private BigDecimal credit;

    @Column(name = "line_memo")
    private String lineMemo;
}
