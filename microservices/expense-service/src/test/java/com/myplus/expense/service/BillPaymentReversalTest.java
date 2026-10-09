package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseBillPayment;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** FP-3b — reversing a bill payment: the books first, then the bill re-opened and the subledger told, once. */
class BillPaymentReversalTest {

    private final ExpenseVoucherRepo vouchers = mock(ExpenseVoucherRepo.class);
    private final ExpenseBillPaymentRepo payments = mock(ExpenseBillPaymentRepo.class);
    private final ExpenseOutboxService outbox = mock(ExpenseOutboxService.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    private ExpenseBillService service;
    private ExpenseVoucher bill;
    private ExpenseBillPayment paid;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<FinanceClient> fp = mock(ObjectProvider.class);
        when(fp.getObject()).thenReturn(finance);
        when(fp.getIfAvailable()).thenReturn(finance);
        when(access.org()).thenReturn(6L);
        when(access.userId()).thenReturn(1L);
        when(access.seesAll()).thenReturn(true);
        when(access.visibleUserId()).thenReturn(null);
        service = new ExpenseBillService(vouchers, payments, outbox, mock(ExpenseAuditService.class), access, fp,
                mock(PlatformTransactionManager.class));

        bill = new ExpenseVoucher();
        bill.setId(77L);
        bill.setOrganizationId(6L);
        bill.setVoucherNo("EXP-000077");
        bill.setVoucherDate(LocalDate.of(2026, 10, 2));
        bill.setPaidFrom("AP");
        bill.setSupplierId(42L);
        bill.setStatus(ExpenseVoucher.POSTED);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(1L);
        l.setAccountCode("6300");
        l.setAmount(new BigDecimal("300"));
        bill.addLine(l);
        bill.setPaidAmount(new BigDecimal("120"));

        paid = new ExpenseBillPayment();
        paid.setId(9L);
        paid.setOrganizationId(6L);
        paid.setVoucherId(77L);
        paid.setAmount(new BigDecimal("120"));
        paid.setStatus(ExpenseBillPayment.RECORDED);
        paid.setReceiptNo("PV-000009");
        paid.setFinancePaymentId(500L);

        when(vouchers.findByIdAndOrganizationId(77L, 6L)).thenReturn(Optional.of(bill));
        when(vouchers.lockForPayment(77L, 6L)).thenReturn(Optional.of(bill));
        when(payments.findById(9L)).thenReturn(Optional.of(paid));
        when(payments.saveAndFlush(any(ExpenseBillPayment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("⭐ the books reverse it, then the bill owes 300 again, the row is REVERSED with PV-…-R, the subledger is told")
    void reverses() {
        when(finance.reversePayment(eq(500L), any())).thenReturn(new PaymentRecordResult(501L, "PV-000009-R", new BigDecimal("-120")));

        var v = service.reversePayment(77L, 9L, "Paid the wrong supplier");

        assertThat(v.status()).isEqualTo("REVERSED");
        assertThat(v.reversalReceiptNo()).isEqualTo("PV-000009-R");
        assertThat(v.reversible()).isFalse();
        assertThat(bill.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(bill.openAmount()).isEqualByComparingTo("300");
        verify(finance).reversePayment(500L, Map.of("reason", "Paid the wrong supplier"));
        verify(outbox).enqueuePayable(bill);
    }

    @Test
    @DisplayName("⭐ the books refuse (a closed period): refused in their words, and nothing here changes")
    void refusalChangesNothing() {
        when(finance.reversePayment(anyLong(), any())).thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, "{\"success\":false,\"message\":\"This period is closed (locked through 2026-10-09).\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.reversePayment(77L, 9L, "x"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("This period is closed (locked through 2026-10-09).");
        assertThat(paid.getStatus()).isEqualTo("RECORDED");
        assertThat(bill.getPaidAmount()).isEqualByComparingTo("120");
        verify(outbox, never()).enqueuePayable(any());
    }

    @Test
    @DisplayName("an answer that is lost says press again — it will not reverse twice (finance is idempotent per payment)")
    void lostAnswer() {
        when(finance.reversePayment(anyLong(), any())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThatThrownBy(() -> service.reversePayment(77L, 9L, "x")).hasMessageContaining("will not reverse twice");
        assertThat(paid.getStatus()).isEqualTo("RECORDED");
    }

    @Test
    @DisplayName("refused before the books are asked: no reason, a user, a Pay Supplier application, a failed payment")
    void refusedEarly() {
        assertThatThrownBy(() -> service.reversePayment(77L, 9L, " ")).isInstanceOf(ValidationException.class);
        paid.setFinancePaymentId(null);
        paid.setReference("BUS-PAYV-6-k1");
        assertThatThrownBy(() -> service.reversePayment(77L, 9L, "x")).hasMessageContaining("Pay Supplier");
        paid.setFinancePaymentId(500L);
        paid.setStatus(ExpenseBillPayment.FAILED);
        assertThatThrownBy(() -> service.reversePayment(77L, 9L, "x")).hasMessageContaining("Only a recorded payment");
        when(access.seesAll()).thenReturn(false);
        assertThatThrownBy(() -> service.reversePayment(77L, 9L, "x"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(finance, never()).reversePayment(anyLong(), any());
    }

    @Test
    @DisplayName("a payment already reversed answers with itself — the books are not asked again")
    void alreadyReversed() {
        paid.setStatus(ExpenseBillPayment.REVERSED);
        paid.setReversalReceiptNo("PV-000009-R");
        assertThat(service.reversePayment(77L, 9L, "again").reversalReceiptNo()).isEqualTo("PV-000009-R");
        verify(finance, never()).reversePayment(anyLong(), any());
    }

    @Test
    @DisplayName("the books' sentence is taken out of their JSON answer")
    void messageOf() {
        assertThat(ExpenseBillService.messageOf("{\"success\":false,\"message\":\"Closed \\\"now\\\"\"}")).isEqualTo("Closed \\\"now\\\"");
        assertThat(ExpenseBillService.messageOf("plain   text")).isEqualTo("plain text");
    }
}
