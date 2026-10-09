package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.expense.dto.ExpenseDtos.PayRequest;
import com.myplus.expense.entity.ExpenseBillPayment;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-7a — paying a member back for an approved claim: through finance as an EMPLOYEE (Cr cash, Dr 2300), once. */
class ClaimPaybackTest {

    private final ExpenseVoucherRepo vouchers = mock(ExpenseVoucherRepo.class);
    private final ExpenseBillPaymentRepo payments = mock(ExpenseBillPaymentRepo.class);
    private final ExpenseOutboxService outbox = mock(ExpenseOutboxService.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    private ExpenseBillService service;
    private ExpenseVoucher claim;
    private ExpenseBillPayment saved;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<FinanceClient> fp = mock(ObjectProvider.class);
        when(fp.getObject()).thenReturn(finance);
        when(fp.getIfAvailable()).thenReturn(finance);
        when(access.org()).thenReturn(6L);
        when(access.userId()).thenReturn(1L);                 // the owner paying
        when(access.canApprove()).thenReturn(true);
        when(access.seesAll()).thenReturn(true);
        when(access.visibleUserId()).thenReturn(null);
        service = new ExpenseBillService(vouchers, payments, outbox, mock(ExpenseAuditService.class), access, fp,
                mock(PlatformTransactionManager.class));

        claim = new ExpenseVoucher();
        claim.setId(40L);
        claim.setOrganizationId(6L);
        claim.setUserId(3L);                                   // the member who paid
        claim.setClaimantName("user@x");
        claim.setVoucherNo("EXP-000040");
        claim.setVoucherDate(LocalDate.of(2026, 10, 2));
        claim.setPaidFrom("EMPLOYEE");
        claim.setClaimStatus(ExpenseVoucher.CLAIM_APPROVED);
        claim.setStatus(ExpenseVoucher.POSTED);
        claim.setPostingStatus(ExpenseVoucher.PS_POSTED_GL);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(1L);
        l.setAccountCode("6200");
        l.setAmount(new BigDecimal("35"));
        claim.addLine(l);

        when(vouchers.findByIdAndOrganizationId(40L, 6L)).thenReturn(Optional.of(claim));
        when(vouchers.lockForPayment(40L, 6L)).thenReturn(Optional.of(claim));
        when(vouchers.findById(40L)).thenReturn(Optional.of(claim));
        when(payments.findByOrganizationIdAndIdempotencyKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(payments.sumPending(40L)).thenReturn(BigDecimal.ZERO);
        when(payments.saveAndFlush(any(ExpenseBillPayment.class))).thenAnswer(i -> {
            ExpenseBillPayment p = i.getArgument(0);
            if (p.getId() == null) p.setId(9L);
            saved = p;
            return p;
        });
        when(payments.findById(9L)).thenAnswer(i -> Optional.ofNullable(saved));
        when(finance.listPayments(anyString(), anyLong())).thenReturn(List.of());
    }

    private static PayRequest cash(String amount) {
        return new PayRequest(new BigDecimal(amount), "CASH", null);
    }

    @Test
    @DisplayName("⭐ an approved claim is paid back to the member as an EMPLOYEE party: no allocation, no supplier subledger")
    void paysBackThroughFinance() {
        PaymentRecordResult res = new PaymentRecordResult();
        res.setId(500L);
        res.setReceiptNo("PV-000500");
        when(finance.recordPayment(any())).thenReturn(res);

        var v = service.pay(40L, cash("35"), "k1");

        ArgumentCaptor<PaymentRecordRequest> sent = ArgumentCaptor.forClass(PaymentRecordRequest.class);
        verify(finance).recordPayment(sent.capture());
        assertThat(sent.getValue().getPartyType()).isEqualTo("EMPLOYEE");
        assertThat(sent.getValue().getPartyId()).isEqualTo(3L);
        assertThat(sent.getValue().getAllocations()).isEmpty();
        assertThat(v.receiptNo()).isEqualTo("PV-000500");
        assertThat(claim.getPaidAmount()).isEqualByComparingTo("35");
        assertThat(claim.openAmount()).isEqualByComparingTo("0");
        verify(outbox, never()).enqueuePayable(any());            // a claim is not a supplier's document
    }

    @Test
    @DisplayName("⭐ only an owner or admin pays a claim back, and never their own")
    void whoMayPay() {
        when(access.canApprove()).thenReturn(false);
        assertThatThrownBy(() -> service.pay(40L, cash("35"), "k2")).isInstanceOf(AccessDeniedException.class);
        when(access.canApprove()).thenReturn(true);
        when(access.userId()).thenReturn(3L);                    // the claimant
        assertThatThrownBy(() -> service.pay(40L, cash("35"), "k3")).hasMessageContaining("cannot pay your own claim back");
        verify(finance, never()).recordPayment(any());
    }

    @Test
    @DisplayName("a waiting claim cannot be paid; nor more than is owed")
    void onlyWhatIsOwed() {
        claim.setClaimStatus(ExpenseVoucher.CLAIM_SUBMITTED);
        assertThatThrownBy(() -> service.pay(40L, cash("35"), "k4")).hasMessageContaining("Only an approved claim");
        claim.setClaimStatus(ExpenseVoucher.CLAIM_APPROVED);
        assertThatThrownBy(() -> service.pay(40L, cash("36"), "k5")).hasMessageContaining("more than is owed on this claim");
        verify(finance, never()).recordPayment(any());
    }

    @Test
    @DisplayName("⭐ a claim paid back cannot be voided until the payment is reversed")
    void voidGuard() {
        claim.applyPayment(new BigDecimal("35"));
        assertThatThrownBy(() -> claim.voidWith("wrong", 1L, LocalDateTime.now()))
                .hasMessageContaining("paid back. Reverse the payment first");
        claim.reversePayment(new BigDecimal("35"));
        claim.voidWith("wrong", 1L, LocalDateTime.now());
        assertThat(claim.getStatus()).isEqualTo(ExpenseVoucher.VOIDED);
    }

    @Test
    @DisplayName("a waiting or rejected claim owes nothing (openAmount 0)")
    void notOwedUntilApproved() {
        claim.setClaimStatus(ExpenseVoucher.CLAIM_REJECTED);
        assertThat(claim.isOwed()).isFalse();
        assertThat(claim.openAmount()).isEqualByComparingTo("0");
    }
}
