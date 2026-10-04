package com.myplus.marketplace.multiseller.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * MKT-1g — one row of a seller's settlement account (V31, source §16). <b>Append-only</b>: there is no update path in
 * the code and no edit route on the API; a mistake is corrected by a new ADJUSTMENT or REVERSAL row. The balance is
 * {@code SUM(credit) − SUM(debit)} over the seller's rows, never a stored column.
 */
@Entity
@Table(name = "mkt_settlement_entry")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceSettlementEntry {

    public static final String CURRENCY = "PKR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The SELLER whose account this row belongs to. */
    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "order_line_id", updatable = false)
    private Long orderLineId;

    @Column(name = "payout_id", updatable = false)
    private Long payoutId;

    /** {@code MarketplaceStatus.LedgerEntryType}. */
    @Column(name = "entry_type", nullable = false, length = 24, updatable = false)
    private String entryType;

    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal debitAmount;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal creditAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "ref", nullable = false, length = 40, updatable = false)
    private String ref;

    @Column(name = "memo", length = 300, updatable = false)
    private String memo;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_by_user_id", updatable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (effectiveAt == null) effectiveAt = createdAt;
        if (currency == null) currency = CURRENCY;
    }
}
