package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.dto.FarmExpenseView;
import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-9b — an old farm row becomes a Cash expense on its own date, tagged to its land when agriculture still lists it. */
class ExpenseRecordFromFarmTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 20);
    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final ExpenseTagService tags = mock(ExpenseTagService.class);
    private final DocumentNumberService numbers = mock(DocumentNumberService.class);
    private ExpenseVoucherService vouchers;

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(10L);
        when(access.userId()).thenReturn(4L);
        when(repo.findByOrganizationIdAndSourceAndSourceRef(any(), any(), any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(ExpenseVoucher.class))).thenAnswer(i -> i.getArgument(0));
        when(numbers.next(anyLong(), anyString())).thenReturn(7L);
        ExpenseCategoryService categories = mock(ExpenseCategoryService.class);
        ExpenseCategory fuel = new ExpenseCategory();
        fuel.setId(5L); fuel.setName("Fuel and transport"); fuel.setAccountCode("6200");
        when(categories.activeCategory(10L, 5L)).thenReturn(fuel);
        vouchers = new ExpenseVoucherService(repo, categories, mock(ExpenseOutboxService.class), mock(ExpenseAuditService.class), access,
                numbers, tags, mock(ExpenseBillPaymentRepo.class), mock(ExpenseSettings.class), mock(ReceiptService.class));
    }

    private static FarmExpenseView row() {
        return new FarmExpenseView(31L, D, new BigDecimal("70.5"), "Diesel for tractor", "Fuel", 4L, "North field", "Wheat", "two cans");
    }

    @Test
    @DisplayName("⭐ Cash, its own date, payee = the expense name, tagged LAND, crop · type · note in the description, keyed FARM")
    void becomesAFarmExpense() {
        when(tags.confirm("LAND", 4L)).thenReturn("North field");
        vouchers.recordFromFarm(row(), 5L);
        ArgumentCaptor<ExpenseVoucher> v = ArgumentCaptor.forClass(ExpenseVoucher.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(v.capture());
        ExpenseVoucher saved = v.getValue();
        assertThat(saved.getPaidFrom()).isEqualTo("CASH");
        assertThat(saved.getVoucherDate()).isEqualTo(D);
        assertThat(saved.getPayeeName()).isEqualTo("Diesel for tractor");
        assertThat(saved.getSource()).isEqualTo("FARM");
        assertThat(saved.getSourceRef()).isEqualTo("31");
        assertThat(saved.getStatus()).isEqualTo("POSTED");
        var l = saved.getLines().get(0);
        assertThat(l.getTagType()).isEqualTo("LAND");
        assertThat(l.getTagId()).isEqualTo(4L);
        assertThat(l.getTagLabel()).isEqualTo("North field");
        assertThat(l.getDescription()).isEqualTo("Wheat · Fuel · two cans");
        assertThat(l.getAmount()).isEqualByComparingTo("70.50");
        assertThat(l.getAccountCode()).isEqualTo("6200");
    }

    @Test
    @DisplayName("a land agriculture no longer lists for the caller: the expense still comes in, untagged")
    void landGoneComesInUntagged() {
        when(tags.confirm("LAND", 4L)).thenThrow(new ValidationException("That land was not found, or is not one of yours."));
        vouchers.recordFromFarm(row(), 5L);
        ArgumentCaptor<ExpenseVoucher> v = ArgumentCaptor.forClass(ExpenseVoucher.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).saveAndFlush(v.capture());
        assertThat(v.getValue().getLines().get(0).getTagType()).isNull();
    }

    @Test
    @DisplayName("⭐ the same row again returns the first voucher (keyed FARM + row id), nothing new is saved")
    void onceOnly() {
        ExpenseVoucher first = new ExpenseVoucher();
        first.setId(9L); first.setVoucherNo("EXP-000300");
        when(repo.findByOrganizationIdAndSourceAndSourceRef(10L, "FARM", "31")).thenReturn(Optional.of(first));
        assertThat(vouchers.recordFromFarm(row(), 5L).getVoucherNo()).isEqualTo("EXP-000300");
        verify(repo, never()).saveAndFlush(any());
        verify(tags, never()).confirm(eq("LAND"), any());
    }
}
