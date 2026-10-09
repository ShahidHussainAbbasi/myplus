package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-2d / E4 — the list's total covers what the caller may see (a user: their own), and paging stays bounded. */
class ExpenseListTotalsTest {

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);

    private ExpenseVoucherService service(Long visibleUserId) {
        when(access.org()).thenReturn(7L);
        when(access.visibleUserId()).thenReturn(visibleUserId);
        return new ExpenseVoucherService(repo, mock(ExpenseCategoryService.class), mock(ExpenseOutboxService.class),
                mock(ExpenseAuditService.class), access, mock(DocumentNumberService.class), mock(ExpenseTagService.class),
                mock(ExpenseBillPaymentRepo.class));
    }

    private static ExpenseVoucherRepo.Totals totals(long count, BigDecimal total) {
        return new ExpenseVoucherRepo.Totals() {
            public long getCount() { return count; }
            public BigDecimal getTotal() { return total; }
        };
    }

    @Test
    @DisplayName("⭐ a user's total is over their own expenses only — the same scope as the list they see")
    void userTotalIsScopedToTheirOwn() {
        LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 9);
        when(repo.totals(7L, 3L, from, to)).thenReturn(totals(2, new BigDecimal("150.00")));

        var t = service(3L).totals(from, to);

        verify(repo).totals(7L, 3L, from, to);
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.total()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("an owner's total is the whole business's (no user filter)")
    void ownerTotalIsTheWholeBusiness() {
        when(repo.totals(eq(7L), isNull(), isNull(), isNull())).thenReturn(totals(40, new BigDecimal("9000")));

        var t = service(null).totals(null, null);

        assertThat(t.count()).isEqualTo(40);
        assertThat(t.total()).isEqualByComparingTo("9000");
    }

    @Test
    @DisplayName("nothing posted in the period → 0 expenses, 0.00 (never null on the wire)")
    void emptyPeriodIsZero() {
        when(repo.totals(any(), any(), any(), any())).thenReturn(totals(0, null));

        var t = service(null).totals(null, null);

        assertThat(t.count()).isZero();
        assertThat(t.total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a page is at most 200 rows whatever is asked, and page/size arrive as asked within that")
    void pageSizeIsBounded() {
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        when(repo.search(eq(7L), isNull(), isNull(), isNull(), isNull(), p.capture()))
                .thenReturn(new PageImpl<ExpenseVoucher>(List.of()));
        ExpenseVoucherService s = service(null);

        s.list(null, null, null, 2, 50);
        assertThat(p.getValue().getPageNumber()).isEqualTo(2);
        assertThat(p.getValue().getPageSize()).isEqualTo(50);

        s.list(null, null, null, -1, 10_000);
        assertThat(p.getValue().getPageNumber()).isZero();
        assertThat(p.getValue().getPageSize()).isEqualTo(200);
    }
}
