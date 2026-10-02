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

    private VoucherPostings() { }

    /** The idempotency key finance dedups on: ONE per voucher and action, so a retry can never post twice. */
    public static String eventKey(ExpenseVoucher v, String action) {
        return "EXP-" + v.getOrganizationId() + "-" + v.getId() + "-" + action;
    }

    public static PostingEventRequest post(ExpenseVoucher v) {
        PaidFrom from = PaidFrom.of(v.getPaidFrom());
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        for (ExpenseVoucherLine l : v.getLines()) byAccount.merge(l.getAccountCode(), l.getAmount(), BigDecimal::add);

        List<PostingLine> lines = new ArrayList<>();
        String memo = v.getPayeeName() != null && !v.getPayeeName().isBlank() ? v.getPayeeName() : v.getVoucherNo();
        byAccount.forEach((code, amt) -> lines.add(PostingLine.builder().accountCode(code).debit(amt).lineMemo(memo).build()));
        lines.add(PostingLine.builder().accountCode(from.creditAccount()).credit(v.getTotal()).lineMemo(from.name()).build());

        return PostingEventRequest.builder()
                .eventType(EXPENSE).eventKey(eventKey(v, "POST"))
                .date(v.getVoucherDate()).ref(v.getVoucherNo())
                .grandTotal(v.getTotal()).method(from.name())
                .lines(lines)
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
