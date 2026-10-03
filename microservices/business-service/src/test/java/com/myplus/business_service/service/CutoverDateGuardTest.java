package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.business_service.config.CutoverDateGuard;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.common.settings.SettingsService;

/**
 * The cutover lock is a FACT about the books: while any opening balance still stands, the cutover date may only be the
 * date those balances are dated by, and the lock cannot come off. Both holes it closes were reproduced 2026-10-02 —
 * the lock was an ordinary switch, and a locked date could be saved blank, leaving the business with no date at all.
 */
class CutoverDateGuardTest {

    private static final long ORG = 46L;
    private static final String CUT = OpeningBalanceService.CUTOVER_KEY;
    private static final String LOCK = OpeningBalanceService.LOCKED_KEY;
    private CustomerHistoryRepo customerHistory;
    private PurchaseRepo purchases;
    private CutoverDateGuard guard;

    @BeforeEach
    void setUp() {
        customerHistory = mock(CustomerHistoryRepo.class);
        purchases = mock(PurchaseRepo.class);
        guard = new CutoverDateGuard(mock(SettingsService.class), customerHistory, purchases);
        when(customerHistory.standingOpeningDates(ORG)).thenReturn(List.of());
        when(purchases.standingOpeningDates(ORG)).thenReturn(List.of());
    }

    private void standing(String customerDay, String supplierDay) {
        when(customerHistory.standingOpeningDates(ORG)).thenReturn(customerDay == null ? List.of()
                : List.of(LocalDateTime.parse(customerDay + "T00:00")));
        when(purchases.standingOpeningDates(ORG)).thenReturn(supplierDay == null ? List.of()
                : List.of(LocalDateTime.parse(supplierDay + "T00:00")));
    }

    @Test
    @DisplayName("nothing standing: the date may be set, moved or cleared, and the lock may come off")
    void free_when_nothing_stands() {
        assertThatCode(() -> guard.check(ORG, CUT, "2026-09-01")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(ORG, CUT, "")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(ORG, LOCK, "false")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠ a standing balance: CLEARING the date is refused (the reproduced hole), and so is moving it")
    void clearing_or_moving_refused_while_a_balance_stands() {
        standing("2026-09-01", null);
        assertThatThrownBy(() -> guard.check(ORG, CUT, "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-09-01").hasMessageContaining("Reverse the opening balances");
        assertThatThrownBy(() -> guard.check(ORG, CUT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(ORG, CUT, "2026-01-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(ORG, CUT, "not a date")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a standing balance: re-saving its own date is fine — and repairs a business the old hole left blank")
    void the_anchored_date_is_accepted() {
        standing(null, "2026-09-01");   // a SUPPLIER opening anchors it just the same
        assertThatCode(() -> guard.check(ORG, CUT, "2026-09-01")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(ORG, CUT, " 2026-09-01 ")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠ a standing balance: the lock cannot be switched off (or reset), but switching it ON is always fine")
    void lock_cannot_come_off_while_a_balance_stands() {
        standing("2026-09-01", "2026-09-01");
        assertThatThrownBy(() -> guard.check(ORG, LOCK, "false")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock cannot come off");
        assertThatThrownBy(() -> guard.checkReset(ORG, LOCK, "false")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.checkReset(ORG, CUT, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> guard.check(ORG, LOCK, "true")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("other settings are none of its business")
    void other_keys_pass() {
        standing("2026-09-01", null);
        assertThatCode(() -> guard.check(ORG, "pos.sale.looseMarkupPct", "10")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("moved from OB-1 case 7: with NO cutover date, posting an opening balance is refused and names the setting")
    void posting_without_a_cutover_is_refused() {
        OpeningBalanceService svc = new OpeningBalanceService();
        SettingsService settings = mock(SettingsService.class);
        when(settings.getText(anyString())).thenReturn(null);
        IdempotencyService idem = mock(IdempotencyService.class);
        when(idem.find(any(), any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(svc, "settingsService", settings);
        ReflectionTestUtils.setField(svc, "idempotencyService", idem);
        assertThatThrownBy(() -> svc.postCustomerOpening(ORG, 1L, 1L, new BigDecimal("5000"), "no cutover", null))
                .hasMessageContaining("cutover");
    }
}
