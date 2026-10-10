package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.expense.dto.ExpenseDtos.LineRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-6b — a member posts up to the owner's limit; above it the expense is saved and waits for an owner or admin. */
class ExpenseUserPostLimitTest {

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final ExpenseSettings settings = mock(ExpenseSettings.class);
    private final ExpenseOutboxService outbox = mock(ExpenseOutboxService.class);
    private final DocumentNumberService numbers = mock(DocumentNumberService.class);
    private ExpenseVoucherService vouchers;

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(3L);
        when(settings.backdateDays()).thenReturn(30);
        when(repo.findByOrganizationIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(ExpenseVoucher.class))).thenAnswer(i -> i.getArgument(0));
        when(numbers.next(anyLong(), anyString())).thenReturn(1L);
        ExpenseCategoryService categories = mock(ExpenseCategoryService.class);
        ExpenseCategory rent = new ExpenseCategory();
        rent.setId(1L); rent.setName("Rent"); rent.setAccountCode("6000");
        when(categories.activeCategory(any(), any())).thenReturn(rent);
        vouchers = new ExpenseVoucherService(repo, categories, outbox, mock(ExpenseAuditService.class), access, numbers,
                mock(ExpenseTagService.class), mock(ExpenseBillPaymentRepo.class), settings, mock(ReceiptService.class));
    }

    private static VoucherRequest rent(String amount) {
        return new VoucherRequest(LocalDate.now(), "CASH", null, null, null,
                List.of(new LineRequest(1L, new BigDecimal(amount), null, null, null)), null, null, null);
    }

    @Test
    @DisplayName("⭐ no limit (the default, blank): a member's expense of any size posts, as before")
    void noLimitByDefault() {
        when(access.canApprove()).thenReturn(false);
        when(settings.userPostLimit()).thenReturn(null);
        assertThat(vouchers.record(rent("5000"), true, "k1").status()).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("⭐ a member above the limit: saved, no number, nothing to the books; up to it posts")
    void aboveTheLimitWaits() {
        when(access.canApprove()).thenReturn(false);
        when(settings.userPostLimit()).thenReturn(new BigDecimal("50"));
        var waiting = vouchers.record(rent("80"), true, "k2");
        assertThat(waiting.status()).isEqualTo("DRAFT");
        assertThat(waiting.voucherNo()).isNull();
        verify(outbox, never()).enqueue(any(), any());
        assertThat(vouchers.record(rent("50"), true, "k3").status()).as("the limit itself is allowed").isEqualTo("POSTED");
    }

    @Test
    @DisplayName("⭐ an owner or admin is never limited; 0 makes every member expense wait")
    void ownersNeverLimited() {
        when(settings.userPostLimit()).thenReturn(BigDecimal.ZERO);
        when(access.canApprove()).thenReturn(true);
        assertThat(vouchers.record(rent("900"), true, "k4").status()).isEqualTo("POSTED");
        when(access.canApprove()).thenReturn(false);
        assertThat(vouchers.record(rent("1"), true, "k5").status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("⭐ posting a waiting draft: refused for a member, in words; an owner or admin posts it")
    void postingTheDraft() {
        when(settings.userPostLimit()).thenReturn(new BigDecimal("50"));
        ExpenseVoucher draft = new ExpenseVoucher();
        draft.setId(9L); draft.setOrganizationId(7L); draft.setUserId(3L); draft.setPaidFrom("CASH");
        draft.setVoucherDate(LocalDate.now());
        com.myplus.expense.entity.ExpenseVoucherLine line = new com.myplus.expense.entity.ExpenseVoucherLine();
        line.setCategoryName("Rent"); line.setAccountCode("6000"); line.setAmount(new BigDecimal("80"));
        draft.addLine(line);
        draft.setTotal(new BigDecimal("80"));
        when(repo.findByIdAndOrganizationId(9L, 7L)).thenReturn(Optional.of(draft));
        when(access.canApprove()).thenReturn(false);
        when(access.visibleUserId()).thenReturn(3L);
        assertThatThrownBy(() -> vouchers.post(9L)).hasMessageContaining("above the 50 a member may post")
                .hasMessageContaining("owner or admin");
        when(access.canApprove()).thenReturn(true);
        when(access.visibleUserId()).thenReturn(null);
        assertThat(vouchers.post(9L).status()).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("the duplicate warning names a waiting expense as such, not as a claim")
    void waitingDuplicateLabel() {
        ExpenseVoucher draft = new ExpenseVoucher();
        draft.setPaidFrom("CASH");
        when(repo.sameExpense(any(), any(), any(), any())).thenReturn(List.of(draft));
        assertThat(vouchers.possibleDuplicates(LocalDate.now(), new BigDecimal("70"), "K-Electric"))
                .containsExactly("an expense waiting to be posted");
    }
}
