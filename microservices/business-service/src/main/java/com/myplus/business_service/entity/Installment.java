package com.myplus.business_service.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.myplus.common.subledger.OpenDoc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import lombok.Data;

/**
 * INST-1 — one dated obligation inside a plan: "installment 3 of 6, 8,000.00, due 15 April".
 *
 * <h3>It IS an {@link OpenDoc}, and that is the whole of requirement R3</h3>
 * {@code SubledgerService.allocate(List<? extends OpenDoc>, amount)} already applies money FIFO, records it in
 * the shared ledger and recomputes the party balance. Implementing the interface here makes
 * {@code receivePayment} work on plans <b>without touching the allocator, finance-service, the GL outbox or
 * the receipt path</b>.
 *
 * <p>Implemented directly on the entity rather than as an inline anonymous class (the shape
 * {@code CustomerService} and {@code VenderService} use for invoices) because an installment's
 * apply-and-restate logic is genuinely its own — a payment can leave it {@code PARTIAL}, which an invoice
 * adapter never has to express. Writing it once here keeps the status transition beside the numbers it
 * depends on.
 *
 * <p>{@code payment_allocations.doc_type} is a free-form {@code VARCHAR(20)}, so {@code "INSTALLMENT"}
 * records in the finance ledger with <b>zero finance-service change</b>.
 *
 * <h3>The sign convention is the opposite of an invoice's — deliberately</h3>
 * {@code CustomerHistory.dueAmount} stores {@code paid − bill}, so it is <b>negative</b> while the customer
 * owes, and its adapter negates on the way out. This stores a plain <b>positive</b> {@code outstanding}.
 * The two must never be added together without normalising; the adapters are where that happens.
 */
@Data
@Entity
@Table(name = "installment", uniqueConstraints = {
        @UniqueConstraint(name = "uq_installment_plan_seq", columnNames = { "plan_id", "seq_no" }) })
public class Installment implements OpenDoc, Serializable {

    private static final long serialVersionUID = 1L;

    /** Scheduled and not yet paid at all. */
    public static final String SCHEDULED = "SCHEDULED";
    /** Some money has landed on it, but not all. */
    public static final String PARTIAL = "PARTIAL";
    /** Settled. */
    public static final String PAID = "PAID";
    /** Forgiven by the owner — it stops being owed and stops being reminded about. */
    public static final String WAIVED = "WAIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The plan this obligation belongs to — a {@code @ManyToOne} so the CHILD owns the foreign key.
     *
     * <p>This started as a unidirectional {@code @OneToMany @JoinColumn} on the plan, which is the shape
     * {@code Order}/{@code OrderItem} uses. That shape makes Hibernate insert the child with a <b>null FK</b>
     * and then UPDATE it — which works only because {@code order_items.order_id} happens to be nullable.
     * {@code installment.plan_id} is {@code NOT NULL}, so the insert failed with
     * <i>"Column 'plan_id' cannot be null"</i> and the plan died after the sale had committed.
     *
     * <p>Exactly the trap OMS O5b hit with {@code shipment_line}, and fixed the same way. <b>A copied pattern
     * can be a working example that only works because of a nullable column.</b>
     *
     * <p>LAZY and excluded from equals/hashCode/toString: a child referencing a parent that holds a list of
     * children recurses otherwise.
     */
    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @jakarta.persistence.JoinColumn(name = "plan_id", nullable = false)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private InstallmentPlan plan;

    @Column(name = "organization_id")
    private Long organizationId;

    /** 1-based. A customer is told "3 of 6", never "2 of 6" for the third payment. */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "paid_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** Positive while owing — see the class javadoc on the sign convention. */
    @Column(name = "outstanding", precision = 19, scale = 2, nullable = false)
    private BigDecimal outstanding = BigDecimal.ZERO;

    /**
     * {@link #SCHEDULED} / {@link #PARTIAL} / {@link #PAID} / {@link #WAIVED}.
     *
     * <p><b>There is no {@code OVERDUE}.</b> Overdue is {@link #isOverdue(LocalDate)} — a predicate over the
     * due date and the balance. Storing it would need a nightly job to flip rows, and on the day that job
     * does not run every screen quietly shows stale truth. The reminder scanner uses the same predicate, so
     * the screen and the reminder cannot disagree.
     *
     * <p>A {@code String} rather than a MySQL {@code ENUM}: adding a value to an ENUM column needs
     * {@code ALTER … MODIFY} and fails as <i>"Data truncated"</i> until it runs.
     */
    @Column(name = "status", length = 16, nullable = false)
    private String status = SCHEDULED;

    @Column(name = "dated")
    private LocalDateTime dated;

    @Column(name = "updated")
    private LocalDateTime updated;

    /** The owning plan's id, for callers that want the key without walking the association. */
    @Transient
    public Long getPlanId() {
        return plan == null ? null : plan.getId();
    }

    // ── OpenDoc ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What is still owed on this installment.
     *
     * <p>A {@code WAIVED} installment reports zero regardless of its stored balance, so the allocator can
     * never apply money to something the owner has forgiven. The stored figure is left intact as the record
     * of what was waived.
     */
    @Override
    public BigDecimal outstanding() {
        if (WAIVED.equals(status)) return BigDecimal.ZERO;
        return outstanding == null ? BigDecimal.ZERO : outstanding;
    }

    /**
     * Apply money to this installment and restate its status.
     *
     * <p>The allocator decides how much lands here; this only records it. {@code outstanding} is floored at
     * zero — an over-application would otherwise create a negative balance that reads as a credit, and
     * overpayment on a plan belongs in store credit ({@code 2200}) through the existing path, never here.
     */
    @Override
    public void apply(BigDecimal applied) {
        if (applied == null || applied.signum() == 0) return;

        BigDecimal paid = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        BigDecimal owed = outstanding == null ? BigDecimal.ZERO : outstanding;

        paidAmount = paid.add(applied);
        owed = owed.subtract(applied);
        outstanding = owed.signum() < 0 ? BigDecimal.ZERO : owed;

        // WAIVED is an owner's decision and money must not silently undo it.
        if (!WAIVED.equals(status)) {
            status = outstanding.signum() == 0 ? PAID
                   : (paidAmount.signum() > 0 ? PARTIAL : SCHEDULED);
        }
        updated = LocalDateTime.now();
    }

    @Override
    public String docType() {
        return "INSTALLMENT";
    }

    @Override
    public Long docId() {
        return id;
    }

    /**
     * How this obligation reads on a receipt: {@code INV-000123/3} — the invoice it belongs to and which
     * installment it is. Set by the service, which knows the invoice number; falls back to the plan's own
     * numbering when it is not.
     */
    @Override
    public String docNo() {
        return docNo != null ? docNo : ("INSTALLMENT/" + seqNo);
    }

    /** Not persisted: composed at read time from the plan's invoice number for the receipt line. */
    @Transient
    private String docNo;

    // ── derived ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Late as at {@code asOf}: due in the past and still owing.
     *
     * <p>Derived rather than stored — see the {@code status} javadoc. Takes the date as a parameter rather
     * than calling {@code LocalDate.now()} so it is testable without a clock, and so a tenant-timezone
     * "today" can be passed in once {@code org.timezone} exists.
     */
    public boolean isOverdue(LocalDate asOf) {
        return asOf != null && dueDate != null
                && dueDate.isBefore(asOf) && outstanding().signum() > 0;
    }

    /** Days late as at {@code asOf}, or 0 when not overdue — the aging bucket's input. */
    public long daysOverdue(LocalDate asOf) {
        if (!isOverdue(asOf)) return 0L;
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOf);
    }
}
