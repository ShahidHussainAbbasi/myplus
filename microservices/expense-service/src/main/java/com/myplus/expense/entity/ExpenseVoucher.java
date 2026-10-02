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

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

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
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why this expense is being voided.");
        if (PS_PENDING.equals(postingStatus))
            throw new IllegalStateException("This expense is still being posted to the books. Void it once it shows In the books.");
        this.status = VOIDED;
        this.voidReason = reason.trim();
        this.voidedBy = by;
        this.voidedAt = now;
    }

    /** True when the voucher reached the ledger, so voiding it must post a reversing journal. */
    public boolean voidNeedsReversal() {
        return VOIDED.equals(status) && PS_POSTED_GL.equals(postingStatus);
    }
}
