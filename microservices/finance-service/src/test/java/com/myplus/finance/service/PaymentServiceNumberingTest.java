package com.myplus.finance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.repository.PaymentRepository;

/**
 * DOC-INT B — where a receipt's number comes from, pure Mockito.
 *
 * <p>The counter's concurrency is {@code ReceiptNumberConcurrencyTest}'s job (it needs a real row lock). This pins
 * the wiring: the right series is asked, the printed format is unchanged for every reader of receipt_no, the seq is
 * stamped for the UNIQUE to bind on, and the number is taken before the insert — i.e. inside the transaction that
 * writes it, so a refused receipt gives it back.
 */
class PaymentServiceNumberingTest {

    private PaymentRepository repo;
    private DocumentNumberService numbers;
    private PaymentService svc;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        numbers = mock(DocumentNumberService.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        svc = new PaymentService(repo, mock(PostingService.class), numbers);
    }

    private static RecordPaymentRequest req(PaymentDirection direction) {
        return RecordPaymentRequest.builder()
                .direction(direction)
                .partyType(PartyType.CUSTOMER).partyId(1L)
                .amount(new BigDecimal("25.00"))
                .build();
    }

    private Payment saved() {
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void a_receipt_takes_its_number_from_the_RECEIPT_series() {
        when(numbers.next(any(), eq("RECEIPT"))).thenReturn(42L);

        PaymentDTO out = svc.record(req(PaymentDirection.RECEIPT));

        assertEquals("RCPT-000042", out.getReceiptNo(), "the printed format every reader already knows");
        assertEquals(42L, saved().getReceiptSeq(), "the seq the UNIQUE binds on");
        verify(numbers, never()).next(any(), eq("DISBURSEMENT"));
    }

    @Test
    void a_vendor_payment_numbers_in_its_own_PV_series() {
        when(numbers.next(any(), eq("DISBURSEMENT"))).thenReturn(7L);

        PaymentDTO out = svc.record(req(PaymentDirection.DISBURSEMENT));

        assertEquals("PV-000007", out.getReceiptNo());
        assertEquals(7L, saved().getReceiptSeq());
        verify(numbers, never()).next(any(), eq("RECEIPT"));
    }

    @Test
    void no_direction_is_a_receipt() {
        when(numbers.next(any(), eq("RECEIPT"))).thenReturn(3L);

        assertEquals("RCPT-000003", svc.record(req(null)).getReceiptNo());
    }

    @Test
    void the_number_is_taken_before_the_insert_that_carries_it() {
        when(numbers.next(any(), anyString())).thenReturn(1L);

        svc.record(req(PaymentDirection.RECEIPT));

        InOrder order = inOrder(numbers, repo);
        order.verify(numbers).next(any(), eq("RECEIPT"));
        order.verify(repo).save(any());
    }

    @Test
    void a_seventh_digit_widens_the_number_rather_than_truncating_it() {
        assertEquals("RCPT-1234567", PaymentService.receiptNo(PaymentDirection.RECEIPT, 1_234_567L));
        assertEquals("PV-000001", PaymentService.receiptNo(PaymentDirection.DISBURSEMENT, 1L));
    }
}
