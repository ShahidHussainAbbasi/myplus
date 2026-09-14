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

    /**
     * DOC-INT B — the number inside {@link #receiptNo}, taken from the per-org counter (V7) and UNIQUE per
     * (organization_id, direction) via {@code uq_pay_org_dir_seq}.
     *
     * <p>NULL on every payment recorded before V7, deliberately: those were numbered {@code COUNT + 1}, some pairs
     * share a number, and they are printed and in customers' hands — so the UNIQUE binds from the first new receipt
     * on and never has to judge history. MySQL treats NULLs as distinct, which is what makes that possible.
     */
    @Column(name = "receipt_seq")
    private Long receiptSeq;

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

    /**
     * OPTIMISTIC LOCK on the ledger row (BLK-0c) — FORWARD protection, and saying so is the point.
     *
     * <p>⚠ <b>Nothing updates a payment today.</b> {@code PaymentService.record} is the only writer of this
     * table and it only INSERTS (traced 2026-09-14: no update path, no native SQL touching {@code payments}).
     * So there is no race for this to lose yet, and no stale-edit refusal a gate could provoke. It is here so
     * that the first feature which edits, re-allocates or reverses a payment inherits a loud refusal instead of
     * last-write-wins — which on money is exactly what STANDARDS §0b refuses — without anyone having to
     * remember to add it. Hibernate puts it in that UPDATE's WHERE clause, so a write made against a copy
     * somebody else has already replaced matches no row and throws.
     *
     * <p>V6's own comment describes a present re-allocation race. That overstates it, and it is left unedited
     * on purpose: Flyway checksums a migration's comments too. See the design doc §8.5.3.
     *
     * <p><b>Not {@code createdAt}</b>: it is set BY the write being validated, has second fidelity, and two
     * writes inside one second compare equal. A counter JPA owns has none of those ambiguities.
     *
     * <p>⚠ {@code NOT NULL DEFAULT 0} in V6 — a NULL version makes Hibernate treat an existing row as
     * TRANSIENT and INSERT it, which on this table would duplicate money.
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version;


    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    public void addAllocation(PaymentAllocation a) {
        a.setPayment(this);
        this.allocations.add(a);
    }
}
