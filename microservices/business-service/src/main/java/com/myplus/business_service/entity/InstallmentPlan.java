package com.myplus.business_service.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import lombok.Data;

/**
 * INST-1 — a handset sold on terms: a down payment at the counter, then N dated obligations.
 *
 * <h3>A plan is a STRUCTURE OVER the existing receivable, not a second one</h3>
 * An installment sale posts exactly what a credit sale posts today — {@code Dr AR / Cr Sales + Tax} — and
 * every receipt posts {@code Dr Cash / Cr AR}, unchanged. This entity holds <b>no money the general ledger
 * does not already know about</b>: Σ(open installments) always equals the plan invoice's outstanding balance.
 * That equality is the INST-1 gate, and it is the reason no new GL account, posting event or
 * {@code gl_outbox} column appears anywhere in this slice.
 *
 * <p>The consequence to preserve: <b>the plan invoice is excluded from the ordinary open-invoice stream</b>,
 * because the plan already represents it. Offering both to the allocator would let one receipt clear the same
 * debt twice.
 */
@Data
@Entity
@Table(name = "installment_plan", uniqueConstraints = {
        @UniqueConstraint(name = "uq_plan_org_seq", columnNames = { "organization_id", "plan_seq" }) })
public class InstallmentPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Built on the sale screen, not yet committed. Never leaves the request that made it. */
    public static final String DRAFT = "DRAFT";
    /** Live: the sale committed and the customer owes the schedule. */
    public static final String ACTIVE = "ACTIVE";
    /** Σ outstanding reached zero. */
    public static final String COMPLETED = "COMPLETED";
    /** The owner moved it to collections. Reversible — customers do come back. */
    public static final String DEFAULTED = "DEFAULTED";
    /** The sale was voided or the handset returned; the plan must not survive its invoice. */
    public static final String CANCELLED = "CANCELLED";
    /** Written off by the owner. Terminal, and it posts a GL entry of its own (INST-6). */
    public static final String WRITTEN_OFF = "WRITTEN_OFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Per-org running number. {@code UNIQUE(organization_id, plan_seq)} is what makes MAX+1 allocation safe
     * under concurrency — the guarantee {@code invoice_seq} has used since slice 22 and {@code quote_seq}
     * since P4b. Without it, two cashiers selling at the same moment mint the same plan number.
     */
    @Column(name = "plan_seq")
    private Long planSeq;

    /** The document's own number, e.g. {@code PLN-000042}. */
    @Column(name = "plan_no", length = 32)
    private String planNo;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "store_id")
    private Long storeId;

    /** Audit: who sold it. Never used for visibility — that is the org scope's job. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * The financed sale. Deliberately not a JPA relation: {@code customer_history} is MyISAM (V1 baseline),
     * which silently ignores foreign keys, so a mapped association would imply a guarantee the storage engine
     * does not provide.
     */
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "invoice_no", length = 32)
    private String invoiceNo;

    @Column(name = "cash_price", precision = 19, scale = 2, nullable = false)
    private BigDecimal cashPrice;

    @Column(name = "down_payment", precision = 19, scale = 2, nullable = false)
    private BigDecimal downPayment = BigDecimal.ZERO;

    /**
     * INST-6, and it stays {@code 0.00} until then.
     *
     * <p>Markup on terms is <b>finance income</b>, not goods revenue, and usually not taxable as a supply of
     * goods. Folding it into the invoice value would overstate sales and tax the financing — corrupting two
     * reports at once. Doing it properly needs a {@code 4400} account plus the five {@code gl_outbox} copy
     * points, which is exactly the change shape that left {@code 4200} empty in every tenant for months.
     * {@code PlanTerms.validate()} refuses a non-zero value today rather than accepting and dropping it.
     */
    @Column(name = "markup_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal markupAmount = BigDecimal.ZERO;

    /** {@code cashPrice − downPayment + markup} — what the schedule must sum to, exactly. */
    @Column(name = "financed_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal financedAmount;

    @Column(name = "installment_count", nullable = false)
    private Integer installmentCount;

    /** {@code WEEKLY / FORTNIGHTLY / MONTHLY}. VARCHAR, never a MySQL ENUM — see the migration's note. */
    @Column(name = "frequency", length = 16, nullable = false)
    private String frequency = "MONTHLY";

    @Column(name = "first_due_date", nullable = false)
    private LocalDate firstDueDate;

    /** When the plan finishes. Stored so "plans ending this quarter" is a query, not a scan. */
    @Column(name = "final_due_date")
    private LocalDate finalDueDate;

    @Column(name = "status", length = 16, nullable = false)
    private String status = DRAFT;

    /**
     * INST-1: the IMEI, as free text. Honest about what it is — a <b>label</b>, not a register.
     *
     * <p>A shop that finances handsets and cannot say <i>which</i> handset cannot repossess, honour a
     * warranty, answer the police, or tell two identical phones on two different plans apart. INST-5 replaces
     * this with a real {@code serial_unit} in inventory-service, with uniqueness and a status of its own.
     */
    @Column(name = "asset_ref", length = 64)
    private String assetRef;

    /** INST-5 — FK into inventory-service's per-unit register, once it exists. */
    @Column(name = "serial_unit_id")
    private Long serialUnitId;

    /** A guarantor is a Party with a role, not a new entity — party-service already models this. */
    @Column(name = "guarantor_party_id")
    private Long guarantorPartyId;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "dated")
    private LocalDateTime dated;

    @Column(name = "updated")
    private LocalDateTime updated;

    /**
     * Optimistic lock. Present from day one: two staff committing the same cart would otherwise produce two
     * plans against one sale, and a customer would owe the handset twice.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * The schedule. {@code EAGER} because every read of a plan wants its rows — the screen, the receipt, the
     * allocator and the reminder scanner all do — and a plan has at most a couple of dozen.
     */
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("seqNo ASC")
    private List<Installment> installments = new ArrayList<>();

    /**
     * Add an installment and set BOTH sides — the only safe way to build this graph.
     *
     * <p>With the child owning the foreign key ({@code @ManyToOne} on {@link Installment}), adding to the
     * list alone leaves {@code plan_id} null and the insert fails against a {@code NOT NULL} column. Same
     * helper, for the same reason, that {@code Shipment.addLine} exists for.
     */
    public void addInstallment(Installment i) {
        i.setPlan(this);
        installments.add(i);
    }

    // ── derived ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What the customer still owes across the whole plan.
     *
     * <p>This is the figure that must equal the plan invoice's outstanding balance. Computed rather than
     * stored: a cached total is one more thing that can disagree with the rows beneath it, and the rows are
     * the record.
     */
    @Transient
    public BigDecimal getTotalOutstanding() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Installment i : installments) sum = sum.add(i.outstanding());
        return sum;
    }

    @Transient
    public BigDecimal getTotalPaid() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Installment i : installments) {
            sum = sum.add(i.getPaidAmount() == null ? BigDecimal.ZERO : i.getPaidAmount());
        }
        return sum;
    }

    /**
     * The next obligation that is still owed, or null when the plan is settled — what the screen shows as
     * "next due" and what a reminder is about.
     */
    @Transient
    public Installment getNextDue() {
        for (Installment i : installments) {
            if (i.outstanding().signum() > 0) return i;
        }
        return null;
    }

    /** How many installments are late as at {@code asOf} — the collections worklist's sort key. */
    @Transient
    public long overdueCount(LocalDate asOf) {
        return installments.stream().filter(i -> i.isOverdue(asOf)).count();
    }

    /** True while the plan can still take money: live, or in collections but not written off. */
    @Transient
    public boolean isCollectable() {
        return ACTIVE.equals(status) || DEFAULTED.equals(status);
    }
}
