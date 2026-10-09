package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingWriteGuard;
import com.myplus.common.settings.SettingsService;
import com.myplus.expense.config.ExpenseSettingsCatalog;

/** EX-2f / E5 — the expense settings: only keys a behaviour reads, bounded where they are written AND where read. */
class ExpenseSettingsTest {

    private final ExpenseSettingsCatalog catalog = new ExpenseSettingsCatalog();

    @Test
    @DisplayName("the catalog registers exactly the keys something reads; backdate 30, paid-from Cash, receipt rule 0 = never")
    void catalogIsWhatIsRead() {
        assertThat(catalog.entries()).extracting(SettingEntry::key)
                .containsExactly(ExpenseSettingsCatalog.BACKDATE_DAYS, ExpenseSettingsCatalog.DEFAULT_PAID_FROM,
                        ExpenseSettingsCatalog.RECEIPT_REQUIRED_ABOVE);          // EX-5: read by ReceiptService.attachOnSave
        assertThat(catalog.entries().get(0).defaultValue()).isEqualTo("30");
        assertThat(catalog.entries().get(1).defaultValue()).isEqualTo("CASH");
        assertThat(catalog.entries().get(2).defaultValue()).isEqualTo("0");
    }

    @Test
    @DisplayName("⭐ the backdate window is refused below 0 and above ten years, in words; 0 and 30 are fine")
    void guardBoundsTheWindow() {
        SettingWriteGuard g = catalog.expenseBackdateGuard();
        assertThatThrownBy(() -> g.check(7L, ExpenseSettingsCatalog.BACKDATE_DAYS, "-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 0 and 3650 days");
        assertThatThrownBy(() -> g.check(7L, ExpenseSettingsCatalog.BACKDATE_DAYS, "100000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> g.check(7L, ExpenseSettingsCatalog.BACKDATE_DAYS, "0")).doesNotThrowAnyException();
        assertThatCode(() -> g.check(7L, ExpenseSettingsCatalog.BACKDATE_DAYS, "30")).doesNotThrowAnyException();
        assertThatCode(() -> g.check(7L, "expense.voucher.defaultPaidFrom", "BANK")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a stored value outside the range cannot widen the window when read (clamped)")
    void readerClamps() {
        SettingsService s = mock(SettingsService.class);
        ExpenseSettings es = new ExpenseSettings(s);
        when(s.getInt(eq(ExpenseSettingsCatalog.BACKDATE_DAYS), anyInt())).thenReturn(99_999);
        assertThat(es.backdateDays()).isEqualTo(3650);
        when(s.getInt(eq(ExpenseSettingsCatalog.BACKDATE_DAYS), anyInt())).thenReturn(-4);
        assertThat(es.backdateDays()).isZero();
        when(s.getInt(eq(ExpenseSettingsCatalog.BACKDATE_DAYS), anyInt())).thenReturn(30);
        assertThat(es.backdateDays()).isEqualTo(30);
    }
}
