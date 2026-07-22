package com.myplus.finance.service;

import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Reliability: a payment and its GL journal are recorded ATOMICALLY. finance owns both the payment ledger and the GL
 * in one DB, so {@code postPayment} runs in {@code record()}'s transaction and a GL-post failure must PROPAGATE out of
 * {@code record()} (letting the surrounding transaction roll the payment back) — never be swallowed, which would drift
 * the books (a recorded payment with no journal). No Spring/DB: this locks in that the best-effort swallow is gone.
 */
class PaymentServiceReliabilityTest {

    @Test
    void glPostFailurePropagates_soThePaymentCannotCommitWithoutItsJournal() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PostingService posting = mock(PostingService.class);
        when(repo.countByDirectionScoped(any(), any(), any())).thenReturn(0L);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));          // echo the saved entity
        doThrow(new RuntimeException("This period is closed"))                 // e.g. a closed period
                .when(posting).postPayment(anyString(), any(BigDecimal.class), any());

        PaymentService svc = new PaymentService(repo, posting);
        RecordPaymentRequest req = RecordPaymentRequest.builder()
                .direction(PaymentDirection.RECEIPT)
                .partyType(PartyType.CUSTOMER).partyId(1L)
                .amount(new BigDecimal("100.00"))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> svc.record(req));
        assertTrue(ex.getMessage().toLowerCase().contains("closed"), "the GL failure is surfaced, not swallowed");
        verify(posting).postPayment(anyString(), any(BigDecimal.class), any());
    }
}
