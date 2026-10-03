package com.myplus.finance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;

/** FP-5a — a settlement delivered twice (outbox retry, lost answer) is ONE payment and ONE journal. */
class PaymentClientRefTest {

    @Test
    void aRepeatedClientRefReturnsTheFirstPayment_noSecondPaymentNoSecondJournal() {
        PaymentRepository repo = mock(PaymentRepository.class);
        PostingService posting = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        Payment first = Payment.builder().id(5L).receiptNo("PV-000009").amount(new BigDecimal("40"))
                .direction(PaymentDirection.DISBURSEMENT).partyType(PartyType.VENDOR).partyId(7L)
                .clientRef("BUS-PAYV-13-k1").allocations(new ArrayList<>()).build();
        when(repo.findByOrganizationIdAndClientRef(any(), any())).thenReturn(Optional.of(first));

        PaymentService svc = new PaymentService(repo, posting, numbers);
        var out = svc.record(RecordPaymentRequest.builder().direction(PaymentDirection.DISBURSEMENT)
                .partyType(PartyType.VENDOR).partyId(7L).amount(new BigDecimal("40")).clientRef("BUS-PAYV-13-k1").build());

        assertEquals("PV-000009", out.getReceiptNo());
        verify(repo, never()).save(any());
        verify(numbers, never()).next(any(), anyString());
        verify(posting, never()).postPayment(anyString(), any(BigDecimal.class), any());
    }
}
