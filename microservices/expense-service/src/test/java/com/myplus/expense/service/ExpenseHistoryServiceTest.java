package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.commerce.contracts.client.DrawerHistoryClient;
import com.myplus.commerce.contracts.dto.DrawerExpenseRequest;
import com.myplus.commerce.contracts.dto.DrawerPayoutView;
import com.myplus.commerce.contracts.dto.ExpenseVoucherRef;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-9a — past till pay-outs: listed with likely duplicates flagged; imported only from business's own rows, once each. */
class ExpenseHistoryServiceTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 3);

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final ExpenseVoucherService vouchers = mock(ExpenseVoucherService.class);
    private final DrawerHistoryClient business = mock(DrawerHistoryClient.class);
    private final com.myplus.commerce.contracts.client.FarmHistoryClient farm = mock(com.myplus.commerce.contracts.client.FarmHistoryClient.class);
    private final ExpenseHistoryService svc = new ExpenseHistoryService(repo, access, vouchers, business, farm);

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(6L);
        when(access.canApprove()).thenReturn(true);
        when(business.unbookedPayouts()).thenReturn(List.of(
                new DrawerPayoutView(11L, D, new BigDecimal("40.00"), "tea", 2L),
                new DrawerPayoutView(12L, D, new BigDecimal("33.50"), "courier", 2L),
                new DrawerPayoutView(13L, D, new BigDecimal("9.00"), "booked already", 2L)));
        when(repo.findByOrganizationIdAndSourceAndSourceRef(eq(6L), eq("DRAWER"), anyString())).thenReturn(Optional.empty());
        ExpenseVoucher booked = new ExpenseVoucher();
        booked.setVoucherNo("EXP-000050");
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "DRAWER", "13")).thenReturn(Optional.of(booked));
        ExpenseVoucher same = new ExpenseVoucher();
        same.setVoucherNo("EXP-000099");
        when(repo.sameDayAmount(any(), any(), any())).thenReturn(List.of());
        when(repo.sameDayAmount(6L, D, new BigDecimal("33.50"))).thenReturn(List.of(same));
        when(vouchers.recordFromDrawer(any())).thenAnswer(i -> new ExpenseVoucherRef(1L, "EXP-0001" + ((DrawerExpenseRequest) i.getArgument(0)).getMovementId()));
    }

    @Test
    @DisplayName("⭐ preview: a pay-out already an expense is not listed; one matching an expense on the same day and amount names it")
    void preview() {
        var rows = svc.tillPreview();
        assertThat(rows).extracting(ExpenseHistoryService.TillRow::ref).containsExactly(11L, 12L);
        assertThat(rows.get(0).matches()).isEmpty();
        assertThat(rows.get(1).matches()).containsExactly("EXP-000099");
    }

    @Test
    @DisplayName("⭐ import: business's own date, amount and reason; an id not unbooked here (or twice) is skipped; booked once")
    void importOnlyFromTheSource() {
        var res = svc.importTill(5L, List.of(11L, 11L, 13L, 999L));
        assertThat(res.imported()).isEqualTo(1);
        assertThat(res.skipped()).isEqualTo(3);
        ArgumentCaptor<DrawerExpenseRequest> r = ArgumentCaptor.forClass(DrawerExpenseRequest.class);
        verify(vouchers, times(1)).recordFromDrawer(r.capture());
        assertThat(r.getValue().getMovementId()).isEqualTo(11L);
        assertThat(r.getValue().getAmount()).isEqualByComparingTo("40.00");
        assertThat(r.getValue().getDate()).isEqualTo(D);
        assertThat(r.getValue().getCategoryId()).isEqualTo(5L);
        assertThat(r.getValue().getStoreId()).isEqualTo(2L);
        verify(business).stampExpenseVoucher(11L, "EXP-000111");
        verify(business).stampExpenseVoucher(13L, "EXP-000050");   // an earlier import's stamp is tried again
    }

    @Test
    @DisplayName("a stamp that does not reach business does not undo the import (the key already keeps it single)")
    void stampIsBestEffort() {
        doThrow(new IllegalStateException("down")).when(business).stampExpenseVoucher(anyLong(), anyString());
        assertThat(svc.importTill(5L, List.of(12L)).imported()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ only an owner or admin; a category and at least one pay-out are required, in words")
    void guards() {
        when(access.canApprove()).thenReturn(false);
        assertThatThrownBy(svc::tillPreview).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.importTill(5L, List.of(11L))).isInstanceOf(AccessDeniedException.class);
        when(access.canApprove()).thenReturn(true);
        assertThatThrownBy(() -> svc.importTill(null, List.of(11L))).hasMessageContaining("category");
        assertThatThrownBy(() -> svc.importTill(5L, List.of())).hasMessageContaining("Tick");
        verify(vouchers, never()).recordFromDrawer(any());
    }

    // ── EX-9b — the farm ──────────────────────────────────────────────────────────────────────────────────────

    private static com.myplus.commerce.contracts.dto.FarmExpenseView farmRow(long id, String amount) {
        return new com.myplus.commerce.contracts.dto.FarmExpenseView(id, D, new BigDecimal(amount), "diesel", "Fuel", 4L, "North field", "Wheat", null);
    }

    @Test
    @DisplayName("⭐ EX-9b — farm preview: an imported row is not listed; a same-day same-amount expense is named")
    void farmPreview() {
        when(farm.notInBooks()).thenReturn(List.of(farmRow(21L, "40.00"), farmRow(22L, "33.50")));
        ExpenseVoucher imported = new ExpenseVoucher();
        imported.setVoucherNo("EXP-000070");
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "FARM", "21")).thenReturn(Optional.of(imported));
        var rows = svc.farmPreview();
        assertThat(rows).extracting(ExpenseHistoryService.FarmRow::ref).containsExactly(22L);
        assertThat(rows.get(0).matches()).containsExactly("EXP-000099");
        assertThat(rows.get(0).landName()).isEqualTo("North field");
    }

    @Test
    @DisplayName("⭐ EX-9b — farm import: only agriculture's own rows, once each, stamped back; a row already in is re-stamped")
    void farmImport() {
        when(farm.notInBooks()).thenReturn(List.of(farmRow(21L, "40.00"), farmRow(22L, "33.50")));
        ExpenseVoucher imported = new ExpenseVoucher();
        imported.setVoucherNo("EXP-000070");
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "FARM", "21")).thenReturn(Optional.of(imported));
        when(vouchers.recordFromFarm(any(), eq(5L))).thenReturn(new ExpenseVoucherRef(2L, "EXP-000222"));
        var res = svc.importFarm(5L, List.of(21L, 22L, 22L, 777L));
        assertThat(res.imported()).isEqualTo(1);
        assertThat(res.skipped()).isEqualTo(3);
        verify(vouchers, times(1)).recordFromFarm(any(), eq(5L));
        verify(farm).stampExpenseVoucher(22L, "EXP-000222");
        verify(farm).stampExpenseVoucher(21L, "EXP-000070");
    }
}
