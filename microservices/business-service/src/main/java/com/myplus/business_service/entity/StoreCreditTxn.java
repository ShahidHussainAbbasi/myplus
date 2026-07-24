package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Store credit (SF-5 Model B): one ledger row per issue/redeem. {@code amount} is + when credit is issued (e.g. a
 * return refunded as credit) and − when redeemed (a STORE_CREDIT tender at checkout). The customer's cached
 * {@code creditBalance} is the running sum. Audit-defensible + reversible (a void re-issues/claws back).
 */
@Entity
@Table(name = "store_credit_txn", indexes = {@Index(name = "idx_scredit_cust", columnList = "organization_id,customer_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoreCreditTxn {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_id")
    private Long customerId;

    /** + issue, − redeem. */
    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    /** RETURN | REDEEM | ADJUST. */
    @Column(name = "reason", length = 32)
    private String reason;

    /** Source document (invoice no). */
    @Column(name = "ref", length = 64)
    private String ref;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "dated")
    private LocalDateTime dated;
}
