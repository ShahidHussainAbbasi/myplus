package com.myplus.expense.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.commerce.contracts.dto.PostingLine;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;

/**
 * Turns a voucher into the ledger event finance-service posts (EX-0b contract). Pure — no Spring, no repository —
 * so the journal a voucher produces is a plain unit test.
 *
 * <pre>
 *   EXPENSE           Dr each line's category account     Cr the PaidFrom account (1000 Cash / 1010 Bank)
 *   EXPENSE_REVERSAL  (lines omitted — finance mirrors the journal it POSTED for this ref, never caller lines)
 * </pre>
 *
 * <p>Debits are grouped by account, so two "Fuel" lines become one 6200 line — the journal reads like a ledger,
 * and the line descriptions still live on the voucher.
 */
public final class VoucherPostings {

    public static final String EXPENSE = "EXPENSE", EXPENSE_REVERSAL = "EXPENSE_REVERSAL";

    /** EX-8d — finance's tax account; input tax is its debit side. */
    static final String INPUT_TAX = "2100";

    private VoucherPostings() { }

    /** The idempotency key finance dedups on: ONE per voucher and action, so a retry can never post twice. */
    public static String eventKey(ExpenseVoucher v, String action) {
        return "EXP-" + v.getOrganizationId() + "-" + v.getId() + "-" + action;
    }

    public static PostingEventRequest post(ExpenseVoucher v) {
        PaidFrom from = PaidFrom.of(v.getPaidFrom());
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        BigDecimal tax = BigDecimal.ZERO;
        for (ExpenseVoucherLine l : v.getLines()) {
            byAccount.merge(l.getAccountCode(), l.netAmount(), BigDecimal::add);
            if (l.getTaxAmount() != null) tax = tax.add(l.getTaxAmount());
        }

        List<PostingLine> lines = new ArrayList<>();
        String memo = v.getPayeeName() != null && !v.getPayeeName().isBlank() ? v.getPayeeName() : v.getVoucherNo();
        byAccount.forEach((code, amt) -> lines.add(PostingLine.builder().accountCode(code).debit(amt).lineMemo(memo).build()));
        // EX-8d — the recoverable input tax to the tax account, where the register nets it against output tax
        if (tax.signum() > 0) lines.add(PostingLine.builder().accountCode(INPUT_TAX).debit(tax).lineMemo("Input tax").build());
        lines.add(PostingLine.builder().accountCode(from.creditAccount()).credit(v.getTotal()).lineMemo(from.name()).build());

        return PostingEventRequest.builder()
                .eventType(EXPENSE).eventKey(eventKey(v, "POST"))
                .date(v.getVoucherDate()).ref(v.getVoucherNo())
                .grandTotal(v.getTotal()).method(from.name())
                .lines(lines)
                .build();
    }

    /** FP-3 — finance's payables subledger keys a bill as (EXPENSE_BILL, voucher id). */
    public static final String PAYABLE = "PAYABLE", PAYABLE_SOURCE = "EXPENSE_BILL";

    /**
     * FP-3 — the bill as finance's payables subledger holds it. Built from the voucher AS IT IS when the outbox sends
     * (never as it was when captured), and versioned by the voucher's own {@code @Version}, which every committed
     * change bumps — so whichever delivery lands last, finance keeps the newest figures.
     */
    public static com.myplus.commerce.contracts.dto.PayableSnapshot payable(ExpenseVoucher v) {
        return com.myplus.commerce.contracts.dto.PayableSnapshot.builder()
                .source(PAYABLE_SOURCE).sourceRef(String.valueOf(v.getId()))
                .sourceVersion(v.getVersion() == null ? 0L : v.getVersion().longValue())
                .partyType("VENDOR").partyId(v.getSupplierId()).partyName(v.getSupplierName())
                .docNo(v.getVoucherNo()).docDate(v.getVoucherDate())
                .amount(v.getTotal())
                .paid(v.getPaidAmount() == null ? BigDecimal.ZERO : v.getPaidAmount())
                // E11 — the subledger follows the BOOKS: a bill whose journal has not landed (still posting, or refused,
                // e.g. a closed period) owes nothing in the ledger GL 2000 mirrors. Before, a refused bill stayed OPEN
                // in the ledger with no credit in 2000, and the daily check "aligned" the books to it through 2990.
                .voided(ExpenseVoucher.VOIDED.equals(v.getStatus()) || !ExpenseVoucher.PS_POSTED_GL.equals(v.getPostingStatus()))
                // FP-4a — a bill is never returned against, so issued = total and the note trail is empty
                .issuedAmount(v.getTotal())
                .dueDate(v.getDueDate())
                .notes(java.util.List.of())
                .build();
    }

    /** Dated the day of the VOID, so voiding in an open period never rewrites a closed one. */
    public static PostingEventRequest reversal(ExpenseVoucher v, LocalDate voidDate) {
        return PostingEventRequest.builder()
                .eventType(EXPENSE_REVERSAL).eventKey(eventKey(v, "VOID"))
                .date(voidDate).ref(v.getVoucherNo())
                .grandTotal(v.getTotal())
                .build();
    }
}
