package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.TenderDTO;
import com.myplus.business_service.entity.Payment;
import com.myplus.business_service.entity.PaymentMethod;
import com.myplus.business_service.repository.PaymentRepo;
import com.myplus.business_service.util.RequestUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PAID-1 — the payment rows net to what the drawer KEPT.
 *
 * <p>The shift's expected cash is Σ payment.amount by method. Recording only the tender (CASH 272 for a 42 bill,
 * INV-000054) made the shift expect 230 more cash than the drawer held. The tender stays as handed over (the audit
 * of cash IN) and one CASH −change row records the cash OUT, so the CASH sum is 42.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRecordChangeTest {

    @Mock PaymentRepo repo;
    @Mock RequestUtil requestUtil;
    PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(repo, requestUtil);
        when(repo.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static TenderDTO tender(String method, String amount) {
        TenderDTO t = new TenderDTO();
        t.setMethod(method);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    @Test
    @DisplayName("⭐ change: the tender as handed over + ONE CASH −change row; the CASH sum is what was kept")
    void changeIsRecordedAsCashOut() {
        service.record(10L, List.of(tender("CASH", "272.00")), 15L, 7L, new BigDecimal("230.00"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(repo, times(2)).save(saved.capture());
        Payment in = saved.getAllValues().get(0), out = saved.getAllValues().get(1);

        assertThat(in.getMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(in.getAmount()).isEqualByComparingTo("272.00");
        assertThat(out.getMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(out.getAmount()).isEqualByComparingTo("-230.00");
        assertThat(out.getReference()).isEqualTo(PaymentService.CHANGE_REFERENCE);
        assertThat(out.getCustomerHistoryId()).isEqualTo(10L);
        assertThat(out.getOrganizationId()).isEqualTo(15L);
        assertThat(in.getAmount().add(out.getAmount())).as("the drawer kept the bill").isEqualByComparingTo("42.00");
    }

    @Test
    @DisplayName("no change → no change row (every exact or part-paid sale is recorded exactly as before)")
    void noChangeNoExtraRow() {
        service.record(11L, List.of(tender("CASH", "42.00")), 15L, 7L, BigDecimal.ZERO);
        verify(repo, times(1)).save(any(Payment.class));
    }

    @Test
    @DisplayName("change on a split tender comes out of the drawer as CASH")
    void splitTenderChangeIsCash() {
        service.record(12L, List.of(tender("CARD", "600.00"), tender("CASH", "500.00")), 15L, 7L,
                new BigDecimal("100.00"));
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(repo, times(3)).save(saved.capture());
        Payment out = saved.getAllValues().get(2);
        assertThat(out.getMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(out.getAmount()).isEqualByComparingTo("-100.00");
    }
}
