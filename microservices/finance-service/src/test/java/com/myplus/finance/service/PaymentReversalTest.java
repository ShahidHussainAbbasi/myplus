package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;

/** FP-3b — reversing a payment: a mirror and the opposite journal, once; refusals in words. */
class PaymentReversalTest {

    private final PaymentRepository repo = mock(PaymentRepository.class);
    private final PostingService posting = mock(PostingService.class);
    private PaymentService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(1L, "owner@test", List.of(), 6L), null, List.of()));
        service = new PaymentService(repo, posting, mock(DocumentNumberService.class));
        when(repo.findByOrganizationIdAndClientRef(any(), any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(Payment.class))).thenAnswer(i -> { Payment p = i.getArgument(0); p.setId(90L); return p; });
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static Payment pv(String method) {
        return Payment.builder().id(5L).organizationId(6L).receiptNo("PV-000009").amount(new BigDecimal("120"))
                .direction(PaymentDirection.DISBURSEMENT).partyType(PartyType.VENDOR).partyId(7L).partyName("K-Electric")
                .method(method).sourceModule("EXPENSE").allocations(new ArrayList<>()).build();
    }

    @Test
    @DisplayName("⭐ EX-7b — a payment that kept its posted accounts is reversed as their exact mirror (an advance: Dr 1300 / Cr 1000)")
    void reversesTheStoredAccounts() {
        Payment advance = pv("CASH");
        advance.setPartyType(PartyType.EMPLOYEE);
        advance.setDebitAccount("1300");
        advance.setCreditAccount("1000");
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.of(advance));

        service.reverse(5L, "Given to the wrong person");

        verify(posting).postPaymentReversalOf(eq("1300"), eq("1000"), eq(new BigDecimal("120")), any(LocalDate.class), eq("PV-000009-R"));
        verify(posting, org.mockito.Mockito.never()).postPaymentReversal(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⭐ a reversed disbursement is a mirror (-120, PV-…-R, no allocations, REV:<id>) and Dr cash / Cr 2000")
    void mirrorAndJournal() {
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.of(pv("CASH")));
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);

        var out = service.reverse(5L, "Paid the wrong supplier");

        verify(repo).saveAndFlush(saved.capture());
        Payment m = saved.getValue();
        assertThat(m.getAmount()).isEqualByComparingTo("-120");
        assertThat(m.getReceiptNo()).isEqualTo("PV-000009-R");
        assertThat(m.getClientRef()).isEqualTo("REV:5");
        assertThat(m.getAllocations()).isEmpty();
        assertThat(m.getSourceModule()).isEqualTo("EXPENSE");      // business's statement keeps excluding it
        assertThat(m.getMethod()).isEqualTo("CASH");
        assertThat(out.getReceiptNo()).isEqualTo("PV-000009-R");
        verify(posting).postPaymentReversal(eq("DISBURSEMENT"), eq(new BigDecimal("120")), eq("CASH"), any(LocalDate.class), eq("PV-000009-R"), any());
    }

    @Test
    @DisplayName("the reversal journal is the payment's journal with its sides swapped (bank too)")
    void reversalLines() {
        List<JournalLineDTO> cash = PostingService.paymentReversalLines("DISBURSEMENT", new BigDecimal("120"), "CASH");
        assertThat(cash.get(0).getAccountCode()).isEqualTo("1000");
        assertThat(cash.get(0).getDebit()).isEqualByComparingTo("120");
        assertThat(cash.get(1).getAccountCode()).isEqualTo("2000");
        assertThat(cash.get(1).getCredit()).isEqualByComparingTo("120");
        List<JournalLineDTO> pay = PostingService.paymentLines("DISBURSEMENT", new BigDecimal("120"), "BANK");
        List<JournalLineDTO> rev = PostingService.paymentReversalLines("DISBURSEMENT", new BigDecimal("120"), "BANK");
        assertThat(rev.get(0).getAccountCode()).isEqualTo(pay.get(1).getAccountCode());
        assertThat(rev.get(1).getAccountCode()).isEqualTo(pay.get(0).getAccountCode());
    }

    @Test
    @DisplayName("⭐ reversing again answers with the first reversal — no second mirror, no second journal")
    void idempotent() {
        Payment first = pv("CASH");
        first.setId(90L);
        first.setReceiptNo("PV-000009-R");
        first.setAmount(new BigDecimal("-120"));
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.of(pv("CASH")));
        when(repo.findByOrganizationIdAndClientRef(6L, "REV:5")).thenReturn(Optional.of(first));

        assertThat(service.reverse(5L, "again").getReceiptNo()).isEqualTo("PV-000009-R");
        verify(repo, never()).saveAndFlush(any());
        verify(posting, never()).postPaymentReversal(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("refused in words: no reason, another tenant's payment, a reversal, a set-off")
    void refusals() {
        assertThatThrownBy(() -> service.reverse(5L, " ")).isInstanceOf(ValidationException.class).hasMessageContaining("why");
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reverse(5L, "x")).isInstanceOf(ResourceNotFoundException.class);
        Payment mirror = pv("CASH");
        mirror.setAmount(new BigDecimal("-120"));
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.of(mirror));
        assertThatThrownBy(() -> service.reverse(5L, "x")).isInstanceOf(ValidationException.class).hasMessageContaining("cannot itself");
        when(repo.findByIdAndOrganizationId(5L, 6L)).thenReturn(Optional.of(pv("SETOFF")));
        assertThatThrownBy(() -> service.reverse(5L, "x")).isInstanceOf(ValidationException.class).hasMessageContaining("set-off");
        verify(posting, never()).postPaymentReversal(anyString(), any(), any(), any(), any(), any());
    }
}
