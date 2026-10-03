package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.finance.dto.AllocationDTO;
import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.dto.SetOffDTOs;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.entity.SetOff;
import com.myplus.finance.repository.PaymentRepository;
import com.myplus.finance.repository.SetOffRepository;

/**
 * DR-4 — finance records both legs of a set-off together, once, and reverses them with mirror payments. What a defect
 * here would break: one leg without the other, a retry that records a second pair, a reversal that statements and
 * party totals never see.
 */
class SetOffServiceTest {

    private PaymentService payments;
    private PaymentRepository paymentRepo;
    private SetOffRepository setOffRepo;
    private PostingService posting;
    private SetOffService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(1L, "owner@test", List.of(), 6L), null, List.of()));
        payments = mock(PaymentService.class);
        paymentRepo = mock(PaymentRepository.class);
        setOffRepo = mock(SetOffRepository.class);
        posting = mock(PostingService.class);
        service = new SetOffService(payments, paymentRepo, setOffRepo, posting);
        when(setOffRepo.findByKey(any(), any())).thenReturn(Optional.empty());
        when(payments.record(any())).thenAnswer(i -> {
            RecordPaymentRequest r = i.getArgument(0);
            boolean receipt = r.getDirection() == PaymentDirection.RECEIPT;
            return PaymentDTO.builder().id(receipt ? 71L : 72L).receiptNo(receipt ? "RCPT-000007" : "PV-000003").build();
        });
        when(paymentRepo.save(any())).thenAnswer(i -> { Payment p = i.getArgument(0); p.setId(p.getAmount().signum() < 0 ? 80L : 81L); return p; });
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static SetOffDTOs.Request request(String custAlloc, String vendAlloc) {
        return SetOffDTOs.Request.builder()
                .idempotencyKey("key-1").setOffNo("SETOFF-000001").customerId(1L).customerName("Usman Traders")
                .venderId(2L).venderName("Usman & Co").amount(new BigDecimal("30000")).reference("SETOFF-000001")
                .customerAllocations(List.of(AllocationDTO.builder().docType("INVOICE").docId(11L).amount(new BigDecimal(custAlloc)).build()))
                .vendorAllocations(List.of(AllocationDTO.builder().docType("PURCHASE").docId(21L).amount(new BigDecimal(vendAlloc)).build()))
                .build();
    }

    @Test
    @DisplayName("records a RECEIPT from the customer and a DISBURSEMENT to the supplier, both method SETOFF, in one go")
    void records_both_legs() {
        var r = service.record(request("30000", "30000"));
        assertThat(r.getReceiptNo()).isEqualTo("RCPT-000007");
        assertThat(r.getVoucherNo()).isEqualTo("PV-000003");
        assertThat(r.isReplay()).isFalse();

        ArgumentCaptor<RecordPaymentRequest> legs = ArgumentCaptor.forClass(RecordPaymentRequest.class);
        verify(payments, times(2)).record(legs.capture());
        assertThat(legs.getAllValues()).extracting(RecordPaymentRequest::getMethod).containsOnly("SETOFF");
        assertThat(legs.getAllValues()).extracting(RecordPaymentRequest::getPartyType).containsExactly(PartyType.CUSTOMER, PartyType.VENDOR);
        assertThat(legs.getAllValues()).extracting(RecordPaymentRequest::getDirection)
                .containsExactly(PaymentDirection.RECEIPT, PaymentDirection.DISBURSEMENT);
        verify(setOffRepo).saveAndFlush(any());
    }

    @Test
    @DisplayName("allocations that do not add up to the amount are rejected before anything is recorded")
    void mismatched_allocations_rejected() {
        assertThatThrownBy(() -> service.record(request("29999.99", "30000"))).isInstanceOf(SetOffService.Rejected.class);
        verify(payments, never()).record(any());
    }

    @Test
    @DisplayName("the same key again answers the first set-off and records nothing")
    void replay() {
        SetOff first = new SetOff();
        first.setReceiptPaymentId(71L); first.setDisbursementPaymentId(72L);
        when(setOffRepo.findByKey(eq(6L), eq("key-1"))).thenReturn(Optional.of(first));
        Payment p71 = Payment.builder().id(71L).receiptNo("RCPT-000007").build();
        Payment p72 = Payment.builder().id(72L).receiptNo("PV-000003").build();
        when(paymentRepo.findById(71L)).thenReturn(Optional.of(p71));
        when(paymentRepo.findById(72L)).thenReturn(Optional.of(p72));

        var r = service.record(request("30000", "30000"));
        assertThat(r.isReplay()).isTrue();
        assertThat(r.getReceiptNo()).isEqualTo("RCPT-000007");
        verify(payments, never()).record(any());
    }

    @Test
    @DisplayName("reversal: two MIRROR payments (negative, same parties) and one mirror journal; then a new key is refused")
    void reversal_writes_mirrors() {
        SetOff s = new SetOff();
        s.setSetOffNo("SETOFF-000001"); s.setAmount(new BigDecimal("30000"));
        s.setReceiptPaymentId(71L); s.setDisbursementPaymentId(72L);
        when(setOffRepo.findByKey(eq(6L), eq("key-1"))).thenReturn(Optional.of(s));
        when(paymentRepo.findById(71L)).thenReturn(Optional.of(Payment.builder().id(71L).direction(PaymentDirection.RECEIPT)
                .partyType(PartyType.CUSTOMER).partyId(1L).amount(new BigDecimal("30000")).receiptNo("RCPT-000007").organizationId(6L).build()));
        when(paymentRepo.findById(72L)).thenReturn(Optional.of(Payment.builder().id(72L).direction(PaymentDirection.DISBURSEMENT)
                .partyType(PartyType.VENDOR).partyId(2L).amount(new BigDecimal("30000")).receiptNo("PV-000003").organizationId(6L).build()));

        var r = service.reverse(SetOffDTOs.ReverseRequest.builder().idempotencyKey("key-1").reversalKey("rev-1")
                .setOffNo("SETOFF-000001").reason("entered twice").build());

        ArgumentCaptor<Payment> mirrors = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepo, times(2)).save(mirrors.capture());
        assertThat(mirrors.getAllValues()).allSatisfy(p -> {
            assertThat(p.getAmount()).isEqualByComparingTo("-30000");
            assertThat(p.getMethod()).isEqualTo("SETOFF");
            assertThat(p.getReceiptSeq()).as("mirrors take no number from the receipt series").isNull();
        });
        assertThat(mirrors.getAllValues()).extracting(Payment::getPartyType).containsExactly(PartyType.CUSTOMER, PartyType.VENDOR);
        assertThat(r.getReceiptNo()).isEqualTo("RCPT-000007-R");
        verify(posting).postSetOffReversal(eq(new BigDecimal("30000")), any(), eq("SETOFF-000001"));
        assertThat(s.getReversedAt()).isNotNull();

        // The same reversal key replays; a NEW key on a reversed set-off is refused.
        assertThat(service.reverse(SetOffDTOs.ReverseRequest.builder().idempotencyKey("key-1").reversalKey("rev-1").build()).isReplay()).isTrue();
        assertThatThrownBy(() -> service.reverse(SetOffDTOs.ReverseRequest.builder().idempotencyKey("key-1").reversalKey("rev-2").build()))
                .isInstanceOf(SetOffService.Rejected.class).hasMessageContaining("already reversed");
        assertThat(s.getReversedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
