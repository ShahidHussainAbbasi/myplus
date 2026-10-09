package com.myplus.expense.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingWriteGuard;
import com.myplus.common.settings.SettingsCatalogProvider;

/**
 * EX-2f / E5 — the expense settings an owner may change (design §6.2). Only keys a behaviour READS are registered
 * (a registered key nothing reads is silently inert — the B2B-P4b lesson):
 * <ul>
 *   <li>{@value #BACKDATE_DAYS} — read by {@code ExpenseVoucherService.build}; replaces the constant 365.</li>
 *   <li>{@value #DEFAULT_PAID_FROM} — read by the Expenses form ({@code expense.js}) when it opens and after a save.</li>
 * </ul>
 * Not yet: {@code userPostLimit} (its design default "users record drafts only" would take posting away from users
 * while drafts are unreachable on screen — E7; it lands with claims/approval, EX-6), {@code receipt.requiredAbove}
 * (EX-5), {@code tax.inputRecoverable} (EX-8).
 */
@Component
public class ExpenseSettingsCatalog implements SettingsCatalogProvider {

    public static final String BACKDATE_DAYS = "expense.voucher.backdateDays";
    public static final String DEFAULT_PAID_FROM = "expense.voucher.defaultPaidFrom";
    public static final String RECEIPT_REQUIRED_ABOVE = "expense.receipt.requiredAbove";
    public static final String INPUT_TAX_RECOVERABLE = "expense.tax.inputRecoverable";   // EX-8d
    public static final int BACKDATE_DEFAULT = 30, BACKDATE_MAX = 3650;

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.intOf(BACKDATE_DAYS, "How far back an expense may be dated (days)",
                        "30 (default): an expense can be dated up to 30 days ago. A closed period still wins: nothing "
                                + "can be dated into books that are closed.",
                        BACKDATE_DEFAULT, "Expenses"),
                SettingEntry.select(DEFAULT_PAID_FROM, "Paid from, by default",
                        "What the New Expense form starts with. Each expense can still be changed before saving.",
                        "CASH", "Expenses",
                        List.of(new SettingEntry.Option("CASH", "Cash"), new SettingEntry.Option("BANK", "Bank"))),
                // EX-5 — read by ReceiptService.attachOnSave
                SettingEntry.money(RECEIPT_REQUIRED_ABOVE, "Receipt required above",
                        "0 (default): a receipt is never required. Above this amount, an expense cannot be saved without "
                                + "a photo or PDF of its receipt.",
                        "0", "Expenses"),
                // EX-8d — read by ExpenseVoucherService.build (a tax part is refused while this is off)
                SettingEntry.bool(INPUT_TAX_RECOVERABLE, "Recover input tax on expenses",
                        "Off (default): an expense is a cost including its tax. On: the form asks how much of each "
                                + "amount is tax you can reclaim; that part goes to the tax account and is netted in the "
                                + "tax register, and only the rest is an expense.",
                        false, "Expenses"));
    }

    /** 0 (today only) to ten years: a negative window or a typo of 100000 days is refused in words. */
    @org.springframework.context.annotation.Bean
    public SettingWriteGuard expenseBackdateGuard() {
        return (org, key, value) -> {
            if (RECEIPT_REQUIRED_ABOVE.equals(key) && new java.math.BigDecimal(value).signum() < 0)
                throw new IllegalArgumentException("The amount above which a receipt is required cannot be negative.");
            if (!BACKDATE_DAYS.equals(key)) return;
            int days = Integer.parseInt(value);
            if (days < 0 || days > BACKDATE_MAX)
                throw new IllegalArgumentException("How far back an expense may be dated must be between 0 and "
                        + BACKDATE_MAX + " days.");
        };
    }
}
