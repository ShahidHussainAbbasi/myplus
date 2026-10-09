package com.myplus.expense.service;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingsService;
import com.myplus.expense.config.ExpenseSettingsCatalog;

import lombok.RequiredArgsConstructor;

/** EX-2f — the expense settings a behaviour reads, typed and bounded at the one place they are read. */
@Component
@RequiredArgsConstructor
public class ExpenseSettings {

    private final SettingsService settings;

    /** How far back a voucher may be dated. Clamped, so a stored value outside the guard's range cannot widen it. */
    public int backdateDays() {
        int d = settings.getInt(ExpenseSettingsCatalog.BACKDATE_DAYS, ExpenseSettingsCatalog.BACKDATE_DEFAULT);
        return Math.max(0, Math.min(d, ExpenseSettingsCatalog.BACKDATE_MAX));
    }

    /** EX-5 — above this a receipt is required; 0 (or anything not positive) = never. */
    public java.math.BigDecimal receiptRequiredAbove() {
        java.math.BigDecimal v = settings.getDecimal(ExpenseSettingsCatalog.RECEIPT_REQUIRED_ABOVE, java.math.BigDecimal.ZERO);
        return v == null || v.signum() < 0 ? java.math.BigDecimal.ZERO : v;
    }
}
