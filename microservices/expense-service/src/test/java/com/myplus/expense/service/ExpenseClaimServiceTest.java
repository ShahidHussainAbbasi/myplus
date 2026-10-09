package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.LineRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-6 — claims: submitted by anyone, decided by an owner/admin who is not the claimant, posted only by approval. */
class ExpenseClaimServiceTest {

    private final ExpenseVoucherService vouchers = mock(ExpenseVoucherService.class);
    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final ReceiptService receipts = mock(ReceiptService.class);
    private ExpenseClaimService claims;

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(1L);          // the approver
        when(access.canApprove()).thenReturn(true);
        when(access.seesAll()).thenReturn(true);
        when(repo.findByOrganizationIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(ExpenseVoucher.class))).thenAnswer(i -> i.getArgument(0));
        claims = new ExpenseClaimService(vouchers, repo, access, receipts, mock(ExpenseAuditService.class));
    }

    private static ExpenseVoucher claim(Long claimant, String claimStatus) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setId(40L);
        v.setOrganizationId(7L);
        v.setUserId(claimant);
        v.setPaidFrom("EMPLOYEE");
        v.setClaimStatus(claimStatus);
        v.setStatus(ExpenseVoucher.DRAFT);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setAmount(new BigDecimal("35"));
        v.addLine(l);
        return v;
    }

    private static VoucherRequest req() {
        return new VoucherRequest(LocalDate.now(), "EMPLOYEE", null, "Petrol pump", null,
                List.of(new LineRequest(1L, new BigDecimal("35"), null, null, null)), null, null, List.of(9L));
    }

    @Test
    @DisplayName("⭐ submit: built on the CLAIM path, waits as SUBMITTED, receipts and the receipt rule applied")
    void submit() {
        ExpenseVoucher built = claim(3L, null);
        when(vouchers.build(eq(7L), any(), eq(true))).thenReturn(built);
        var v = claims.submit(req(), "k1");
        assertThat(v.claimStatus()).isEqualTo("SUBMITTED");
        verify(vouchers).build(eq(7L), any(), eq(true));
        verify(receipts).attachOnSave(built, List.of(9L));
        verify(vouchers, never()).postInTx(any());               // not in the books until approved
    }

    @Test
    @DisplayName("claims switched off → refused before anything is built")
    void switchedOff() {
        doThrow(new ValidationException("Expense claims are not switched on")).when(access).assertClaimsOn();
        assertThatThrownBy(() -> claims.submit(req(), "k1")).hasMessageContaining("not switched on");
        verify(vouchers, never()).build(anyLong(), any(), eq(true));
    }

    @Test
    @DisplayName("⭐ approve: posted through the path every expense takes; decided by and when are kept")
    void approve() {
        ExpenseVoucher c = claim(3L, "SUBMITTED");
        when(vouchers.visible(40L)).thenReturn(c);
        var v = claims.approve(40L);
        verify(vouchers).postInTx(c);
        assertThat(c.getClaimStatus()).isEqualTo("APPROVED");
        assertThat(c.getDecidedBy()).isEqualTo(1L);
        assertThat(v.claimStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("⭐ nobody approves their own claim — not even the owner")
    void noSelfApproval() {
        when(vouchers.visible(40L)).thenReturn(claim(1L, "SUBMITTED"));
        assertThatThrownBy(() -> claims.approve(40L)).hasMessageContaining("cannot approve your own claim");
        assertThatThrownBy(() -> claims.reject(40L, "no")).hasMessageContaining("cannot decide your own claim");
        verify(vouchers, never()).postInTx(any());
    }

    @Test
    @DisplayName("a user (or the platform operator) cannot approve or reject")
    void onlyOwnerOrAdminDecides() {
        when(access.canApprove()).thenReturn(false);
        when(vouchers.visible(40L)).thenReturn(claim(3L, "SUBMITTED"));
        assertThatThrownBy(() -> claims.approve(40L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> claims.reject(40L, "x")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reject needs a reason the claimant sees; nothing is posted")
    void reject() {
        ExpenseVoucher c = claim(3L, "SUBMITTED");
        when(vouchers.visible(40L)).thenReturn(c);
        assertThatThrownBy(() -> claims.reject(40L, " ")).hasMessageContaining("Say why");
        claims.reject(40L, "No receipt for the fuel");
        assertThat(c.getClaimStatus()).isEqualTo("REJECTED");
        assertThat(c.getDecisionNote()).isEqualTo("No receipt for the fuel");
        verify(vouchers, never()).postInTx(any());
    }

    @Test
    @DisplayName("a decided claim cannot be decided again; only a waiting one can be withdrawn, by its claimant")
    void decidedOnce() {
        when(vouchers.visible(40L)).thenReturn(claim(3L, "APPROVED"));
        assertThatThrownBy(() -> claims.approve(40L)).hasMessageContaining("already decided");
        assertThatThrownBy(() -> claims.withdraw(40L)).hasMessageContaining("already decided");

        ExpenseVoucher waiting = claim(3L, "SUBMITTED");
        when(vouchers.visible(40L)).thenReturn(waiting);
        when(access.seesAll()).thenReturn(false);
        when(access.userId()).thenReturn(4L);                     // a colleague
        assertThatThrownBy(() -> claims.withdraw(40L)).isInstanceOf(AccessDeniedException.class);
        when(access.userId()).thenReturn(3L);                     // the claimant
        assertThat(claims.withdraw(40L).claimStatus()).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("an ordinary expense is not a claim")
    void notAClaim() {
        ExpenseVoucher cash = claim(3L, null);
        cash.setPaidFrom("CASH");
        when(vouchers.visible(40L)).thenReturn(cash);
        assertThatThrownBy(() -> claims.approve(40L)).hasMessageContaining("not a claim");
    }
}
