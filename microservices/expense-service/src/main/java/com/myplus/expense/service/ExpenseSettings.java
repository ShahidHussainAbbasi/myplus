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
}
