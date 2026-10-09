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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.expense.entity.ExpenseAdvanceBalance;
import com.myplus.expense.entity.ExpenseAdvanceMovement;
import com.myplus.expense.repository.ExpenseAdvanceBalanceRepo;
import com.myplus.expense.repository.ExpenseAdvanceMovementRepo;
import com.myplus.expense.service.ExpenseAdvanceService.AdvanceRequest;

/** EX-7b — advances: to staff only, by an owner or admin who is not the recipient; recorded once; the balance exact. */
class ExpenseAdvanceServiceTest {

    private final ExpenseAdvanceBalanceRepo balances = mock(ExpenseAdvanceBalanceRepo.class);
    private final ExpenseAdvanceMovementRepo movements = mock(ExpenseAdvanceMovementRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    private ExpenseAdvanceService service;
    private ExpenseAdvanceMovement saved;
    private ExpenseAdvanceBalance balance;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<FinanceClient> fp = mock(ObjectProvider.class);
        when(fp.getObject()).thenReturn(finance);
        when(fp.getIfAvailable()).thenReturn(finance);
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(1L);                 // the owner
        when(access.canApprove()).thenReturn(true);
        when(staff.staffMember(3L)).thenReturn(Optional.of(new StaffDirectory.Member(3L, "User Education", "user@x", "USER")));
        when(staff.staffMember(9L)).thenReturn(Optional.empty());   // a guardian, say
        when(movements.findByOrganizationIdAndIdempotencyKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(movements.saveAndFlush(any(ExpenseAdvanceMovement.class))).thenAnswer(i -> {
            ExpenseAdvanceMovement m = i.getArgument(0);
            if (m.getId() == null) m.setId(20L);
            saved = m;
            return m;
        });
        when(movements.findById(20L)).thenAnswer(i -> Optional.ofNullable(saved));
        when(finance.listPayments(anyString(), anyLong())).thenReturn(List.of());
        balance = new ExpenseAdvanceBalance();
        balance.setOrganizationId(7L);
        balance.setUserId(3L);
        balance.setBalance(new BigDecimal("50.00"));
        when(balances.lock(7L, 3L)).thenReturn(Optional.of(balance));
        service = new ExpenseAdvanceService(balances, movements, access, staff, mock(ExpenseAuditService.class), fp,
                mock(PlatformTransactionManager.class));
    }

    private static PaymentRecordResult pv(String no) {
        PaymentRecordResult r = new PaymentRecordResult();
        r.setId(800L);
        r.setReceiptNo(no);
        return r;
    }

    @Test
    @DisplayName("⭐ an advance given: an EMPLOYEE disbursement with purpose ADVANCE; the balance grows only once finance has it")
    void give() {
        when(finance.recordPayment(any())).thenReturn(pv("PV-000800"));
        var v = service.give(new AdvanceRequest(3L, new BigDecimal("30"), "CASH", "Market run"), "k1");

        ArgumentCaptor<PaymentRecordRequest> sent = ArgumentCaptor.forClass(PaymentRecordRequest.class);
        verify(finance).recordPayment(sent.capture());
        assertThat(sent.getValue().getDirection()).isEqualTo("DISBURSEMENT");
        assertThat(sent.getValue().getPartyType()).isEqualTo("EMPLOYEE");
        assertThat(sent.getValue().getPartyId()).isEqualTo(3L);
        assertThat(sent.getValue().getPurpose()).isEqualTo("ADVANCE");
        assertThat(sent.getValue().getReference()).isEqualTo("EXPA-7-20");
        assertThat(v.status()).isEqualTo("RECORDED");
        assertThat(v.receiptNo()).isEqualTo("PV-000800");
        assertThat(balance.getBalance()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("⭐ only to staff, never to yourself, and only by an owner or admin")
    void whoMayGive() {
        assertThatThrownBy(() -> service.give(new AdvanceRequest(9L, BigDecimal.TEN, "CASH", null), "k2"))
                .hasMessageContaining("not staff of this business");
        assertThatThrownBy(() -> service.give(new AdvanceRequest(1L, BigDecimal.TEN, "CASH", null), "k3"))
                .hasMessageContaining("cannot give yourself an advance");
        when(access.canApprove()).thenReturn(false);
        assertThatThrownBy(() -> service.give(new AdvanceRequest(3L, BigDecimal.TEN, "CASH", null), "k4"))
                .isInstanceOf(AccessDeniedException.class);
        verify(finance, never()).recordPayment(any());
    }

    @Test
    @DisplayName("⭐ taking back: a RECEIPT with purpose ADVANCE, reserved off the balance first; never more than is held")
    void takeBack() {
        assertThatThrownBy(() -> service.takeBack(new AdvanceRequest(3L, new BigDecimal("60"), "CASH", null), "k5"))
                .hasMessageContaining("more than this member holds");
        verify(finance, never()).recordPayment(any());

        when(finance.recordPayment(any())).thenReturn(pv("RCPT-000801"));
        var v = service.takeBack(new AdvanceRequest(3L, new BigDecimal("20"), "BANK", null), "k6");
        ArgumentCaptor<PaymentRecordRequest> sent = ArgumentCaptor.forClass(PaymentRecordRequest.class);
        verify(finance).recordPayment(sent.capture());
        assertThat(sent.getValue().getDirection()).isEqualTo("RECEIPT");
        assertThat(sent.getValue().getPurpose()).isEqualTo("ADVANCE");
        assertThat(v.status()).isEqualTo("RECORDED");
        assertThat(balance.getBalance()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a take-back the books refuse puts the reserved amount back on the balance (FAILED)")
    void refusedTakeBackReleases() {
        when(finance.recordPayment(any())).thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, "{\"message\":\"The period is closed\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.takeBack(new AdvanceRequest(3L, new BigDecimal("20"), "CASH", null), "k7"))
                .hasMessageContaining("The period is closed");
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(balance.getBalance()).isEqualByComparingTo("50");
    }
}
