package com.myplus.finance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One payment in the shared ledger — a receipt (AR) now, a disbursement (AP) later. GL-ready: it carries the
 * source module + party + method + date and nullable debit/credit account slots the future General Ledger will
 * populate when it posts from these rows. Tenant-scoped (organization_id + user_id NULL-fallback).
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_pay_party", columnList = "organization_id,party_type,party_id"),
        @Index(name = "idx_pay_user", columnList = "user_id,party_type,party_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentDirection direction;      // RECEIPT (AR) | DISBURSEMENT (AP)

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false, length = 20)
    private PartyType partyType;

    @Column(name = "party_id")
    private Long partyId;

    @Column(name = "party_name")
    private String partyName;                // denormalized for listing/receipts

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(length = 30)
    private String method;                   // CASH | CARD | BANK | CHEQUE | ...

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "reference")
    private String reference;                // cheque / txn number

    @Column(name = "source_module", length = 30)
    private String sourceModule;             // BUSINESS | EDUCATION | ...

    @Column(name = "receipt_no")
    private String receiptNo;                // per-org receipt sequence (RCPT-000123)

    @Column(name = "note")
    private String note;

    // GL-ready slots — populated when the General Ledger (Task #3) posts these entries.
    @Column(name = "debit_account")
    private String debitAccount;
    @Column(name = "credit_account")
    private String creditAccount;

    @Column(name = "organization_id")
    private Long organizationId;             // tenant scope

    @Column(name = "user_id")
    private Long userId;                     // audit + NULL-org fallback scope

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    public void addAllocation(PaymentAllocation a) {
        a.setPayment(this);
        this.allocations.add(a);
    }
}
