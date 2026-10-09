package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.expense.dto.ExpenseDtos.LineRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-6 — the existing paths cannot be used to put a claim in the books without its approval. */
class ClaimGuardsTest {

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final DocumentNumberService numbers = mock(DocumentNumberService.class);

    private ExpenseVoucherService service() {
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(3L);
        when(access.visibleUserId()).thenReturn(null);
        when(repo.findByOrganizationIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        ExpenseSettings settings = mock(ExpenseSettings.class);
        when(settings.backdateDays()).thenReturn(30);
        return new ExpenseVoucherService(repo, mock(ExpenseCategoryService.class), mock(ExpenseOutboxService.class),
                mock(ExpenseAuditService.class), access, numbers, mock(ExpenseTagService.class),
                mock(ExpenseBillPaymentRepo.class), settings, mock(ReceiptService.class));
    }

    @Test
    @DisplayName("⭐ recording an expense 'paid from EMPLOYEE' directly is refused — it must go as a claim")
    void recordRefusesEmployee() {
        VoucherRequest r = new VoucherRequest(LocalDate.now(), "EMPLOYEE", null, null, null,
                List.of(new LineRequest(1L, new BigDecimal("10"), null, null, null)), null, null, null);
        assertThatThrownBy(() -> service().record(r, true, "k")).hasMessageContaining("is a claim");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("⭐ the 'post a draft' command refuses a claim (it would skip the approval); delete refuses it too")
    void postAndDeleteRefuseClaims() {
        ExpenseVoucher c = new ExpenseVoucher();
        c.setId(40L);
        c.setOrganizationId(7L);
        c.setUserId(3L);
        c.setPaidFrom("EMPLOYEE");
        c.setStatus(ExpenseVoucher.DRAFT);
        c.setClaimStatus(ExpenseVoucher.CLAIM_SUBMITTED);
        when(repo.findByIdAndOrganizationId(40L, 7L)).thenReturn(Optional.of(c));
        ExpenseVoucherService s = service();
        assertThatThrownBy(() -> s.post(40L)).hasMessageContaining("approves it");
        assertThatThrownBy(() -> s.deleteDraft(40L)).hasMessageContaining("withdrawn, not deleted");
        verify(numbers, never()).next(any(), any());
    }
}
