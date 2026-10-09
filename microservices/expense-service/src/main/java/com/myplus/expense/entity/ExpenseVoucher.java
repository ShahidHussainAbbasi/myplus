package com.myplus.expense.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One payment out — the direct expense voucher (QuickBooks "Expense", Xero "Spend Money", Tally "Payment").
 *
 * <h3>Three axes, deliberately not one status</h3>
 * {@link #status} is the DOCUMENT (DRAFT → POSTED → VOIDED). {@link #postingStatus} is what the LEDGER has
 * answered (NONE → PENDING → POSTED_GL | FAILED), stamped by the outbox when finance replies. A single field
 * would let "POSTED" mean "we sent it" — the optimistic claim STANDARDS §0b forbids for money.
 *
 * <h3>The state machine lives here</h3>
 * Every transition is a method that refuses an illegal move, so no service or controller can set a status
 * directly. A posted voucher is never edited or deleted; it is voided by a reversing journal.
 */
@Entity
@Table(name = "expense_voucher")
@Getter @Setter
public class ExpenseVoucher {

    public static final String DRAFT = "DRAFT", POSTED = "POSTED", VOIDED = "VOIDED";
    public static final String SOURCE_MANUAL = "MANUAL", SOURCE_DRAWER = "DRAWER";
    /** EX-6 — a claim's own state (the voucher stays DRAFT until it is approved and posted). */
    public static final String CLAIM_SUBMITTED = "SUBMITTED", CLAIM_APPROVED = "APPROVED", CLAIM_REJECTED = "REJECTED",
            CLAIM_WITHDRAWN = "WITHDRAWN";
    public static final String PS_NONE = "NONE", PS_PENDING = "PENDING", PS_POSTED_GL = "POSTED_GL", PS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "voucher_no", length = 20)
    private String voucherNo;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(name = "paid_from", nullable = false, length = 16)
    private String paidFrom;

    @Column(name = "payee_name", length = 160)
    private String payeeName;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 16)
    private String status = DRAFT;

    @Column(name = "posting_status", nullable = false, length = 16)
    private String postingStatus = PS_NONE;

    @Column(name = "posting_error", length = 500)
    private String postingError;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** EX-3 — MANUAL (Expenses screen) or DRAWER (a till pay-out). A DRAWER voucher is corrected at the till. */
    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_MANUAL;

    /** The originating record's id (the drawer movement) — with source, the idempotent-receiver key. */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    /** FP-3 — a BILL (paid_from AP) is owed to this business supplier, confirmed with business-service at save. */
    @Column(name = "supplier_id")
    private Long supplierId;

    /** The supplier's name as business-service labelled it at save (snapshot). */
    @Column(name = "supplier_name", length = 160)
    private String supplierName;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** FP-3 — STAMPED by each recorded payment ({@link #applyPayment}); open = total − paidAmount. */
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    // ── EX-6 — a claim (paid_from EMPLOYEE). NULL on every other voucher. ─────────────────────────────
    @Column(name = "claim_status", length = 16)
    private String claimStatus;

    /** Who paid it — written at submit (the trail must read after the person has left). */
    @Column(name = "claimant_name", length = 160)
    private String claimantName;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    public boolean isClaim() { return "EMPLOYEE".equals(paidFrom); }

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<ExpenseVoucherLine> lines = new ArrayList<>();

    public void addLine(ExpenseVoucherLine l) {
        l.setVoucher(this);
        l.setLineNo(lines.size() + 1);
        lines.add(l);
        stampTotal();
    }

    /** The total is STAMPED from the lines in the writing transaction — never recomputed on read. */
    public void stampTotal() {
        BigDecimal t = BigDecimal.ZERO;
        for (ExpenseVoucherLine l : lines) t = t.add(l.getAmount());
        this.total = t.setScale(2, RoundingMode.HALF_UP);
    }

    /** DRAFT → POSTED. The ledger has not answered yet, so postingStatus becomes PENDING, never POSTED_GL. */
    public void post(String number, LocalDateTime now) {
        if (!DRAFT.equals(status)) throw new IllegalStateException("Only a draft can be posted (this one is " + status + ").");
        if (lines.isEmpty() || total.signum() <= 0) throw new IllegalStateException("An expense needs at least one line with an amount.");
        this.voucherNo = number;
        this.status = POSTED;
        this.postingStatus = PS_PENDING;
        this.postedAt = now;
    }

    /**
     * POSTED → VOIDED. Refused while the ledger has not answered: reversing a journal finance has not written yet
     * would be refused there, so the owner is told to wait. A FAILED posting never reached the books, so its void
     * needs no reversal — {@link #voidNeedsReversal()} tells the service which case this is.
     */
    public void voidWith(String reason, Long by, LocalDateTime now) {
        if (!POSTED.equals(status)) throw new IllegalStateException("Only a posted expense can be voided (this one is " + status + ").");
        // EX-3 — the cash really left the till. Voiding the expense alone would make the books disagree with the
        // drawer; the correction belongs at the till, where the shift report sees it too.
        if (SOURCE_DRAWER.equals(source))
            throw new IllegalStateException("This expense was paid out of the till. Correct it at the till (a pay-in), so the drawer and the books stay in step.");
        // FP-3 — money already left for this bill. Voiding the bill alone would leave a payment to the supplier
        // with nothing owed behind it; the payment is reversed first, then the bill.
        if (isBill() && paidAmount != null && paidAmount.signum() > 0)
            throw new IllegalStateException("This bill has payments against it. Reverse the payments first, then void the bill.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why this expense is being voided.");
        if (PS_PENDING.equals(postingStatus))
            throw new IllegalStateException("This expense is still being posted to the books. Void it once it shows In the books.");
        this.status = VOIDED;
        this.voidReason = reason.trim();
        this.voidedBy = by;
        this.voidedAt = now;
    }

    /** FP-3 — a bill: posted to Accounts Payable, owed to {@link #supplierId} until paid. */
    public boolean isBill() {
        return "AP".equals(paidFrom);
    }

    /** What is still owed on a bill (0 for anything else, and for a voided bill). */
    public BigDecimal openAmount() {
        if (!isBill() || VOIDED.equals(status)) return BigDecimal.ZERO;
        return total.subtract(paidAmount == null ? BigDecimal.ZERO : paidAmount);
    }

    /**
     * FP-3 — a payment finance has CONFIRMED. Refuses anything that would pay more than is owed; the service
     * has already checked against the reserved (pending) payments, this is the entity's own last word.
     */
    /** FP-3b — a payment the books have REVERSED: what it paid is owed again. Never below zero paid. */
    public void reversePayment(BigDecimal amount) {
        if (!isBill()) throw new IllegalStateException("Only a bill's payment can be reversed.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A reversal needs an amount greater than zero.");
        BigDecimal paid = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        if (amount.compareTo(paid) > 0) throw new IllegalStateException("That is more than has been paid on this bill (" + paid + ").");
        this.paidAmount = paid.subtract(amount);
    }

    public void applyPayment(BigDecimal amount) {
        if (!isBill()) throw new IllegalStateException("Only a bill can be paid — this expense was already paid when recorded.");
        if (!POSTED.equals(status)) throw new IllegalStateException("Only a posted bill can be paid (this one is " + status + ").");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("A payment needs an amount greater than zero.");
        if (amount.compareTo(openAmount()) > 0) throw new IllegalStateException("That is more than is owed on this bill (" + openAmount() + ").");
        this.paidAmount = (paidAmount == null ? BigDecimal.ZERO : paidAmount).add(amount);
    }

    /** True when the voucher reached the ledger, so voiding it must post a reversing journal. */
    public boolean voidNeedsReversal() {
        return VOIDED.equals(status) && PS_POSTED_GL.equals(postingStatus);
    }
}
