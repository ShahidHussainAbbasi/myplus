package com.myplus.expense.service;

/**
 * Where the money came from — the STRATEGY that decides the credit side of an expense's journal.
 *
 * <p>The paying source, not the category, decides the document (QuickBooks Expense vs Bill, Xero Spend Money vs
 * Bills, Odoo "Paid by"). EX-1 ships the two a business pays from directly. Each later source is one more value
 * here, with its own account, and finance-service's ExpensePostingRules already accepts it:
 * DRAWER → 1000 (EX-3), AP → 2000 (EX-4), EMPLOYEE → 2300 (EX-6).
 */
public enum PaidFrom {

    /** Cash in hand — 1000 Cash. */
    CASH("1000"),

    /** Bank transfer, card or cheque — 1010 Bank. */
    BANK("1010"),

    /**
     * EX-3 — cash paid out of the till during a shift. Same account as CASH (1000): the drawer IS the business's
     * cash. Its own value because the voucher's origin differs — it is created by business-service from the
     * drawer movement, and corrected at the till rather than voided here.
     */
    DRAWER("1000"),

    /**
     * FP-3 = EX-4 — a BILL: nothing is paid yet, the expense is owed to a supplier — 2000 Accounts Payable. Paying
     * it later is a disbursement in finance (Dr 2000 / Cr cash·bank), so the expense is in the books the day the bill
     * arrives (accrual) and the cash leaves the day it is paid.
     */
    AP("2000");

    private final String creditAccount;

    PaidFrom(String creditAccount) { this.creditAccount = creditAccount; }

    /** The account this payment is credited to. */
    public String creditAccount() { return creditAccount; }

    /** Resolve an incoming value, refusing anything not listed here. */
    public static PaidFrom of(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Say how the expense was paid: cash, bank, or a bill to pay later.");
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("An expense can be paid from cash or bank, or be a bill to pay later (not " + raw + ").");
        }
    }
}
