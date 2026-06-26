package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.TenderDTO;

import org.junit.jupiter.api.Test;

/**
 * G5 payments (slice 37) — pure settlement, always runs (no Spring/DB). paid = Σ non-credit tenders;
 * due = max(0, grandTotal − paid); change = overpayment; mode = single method or SPLIT.
 */
class PaymentServiceTest {

    private static TenderDTO tender(String method, String amount) {
        TenderDTO t = new TenderDTO();
        t.setMethod(method);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    @Test
    void cash_exact_payment_clears_the_due_with_no_change() {
        SettleResult r = PaymentService.settle(new BigDecimal("117.00"), List.of(tender("CASH", "117.00")));
        assertThat(r.paid()).isEqualByComparingTo("117.00");
        assertThat(r.due()).isEqualByComparingTo("0.00");
        assertThat(r.change()).isEqualByComparingTo("0.00");
        assertThat(r.paymentMode()).isEqualTo("CASH");
    }

    @Test
    void cash_overpayment_returns_change() {
        SettleResult r = PaymentService.settle(new BigDecimal("100.00"), List.of(tender("CASH", "150.00")));
        assertThat(r.paid()).isEqualByComparingTo("150.00");
        assertThat(r.due()).isEqualByComparingTo("0.00");
        assertThat(r.change()).isEqualByComparingTo("50.00");
    }

    @Test
    void credit_sale_is_unpaid_and_fully_due() {
        SettleResult r = PaymentService.settle(new BigDecimal("100.00"), List.of(tender("CREDIT", "100.00")));
        assertThat(r.paid()).isEqualByComparingTo("0.00");   // credit is not "paid"
        assertThat(r.due()).isEqualByComparingTo("100.00");
        assertThat(r.paymentMode()).isEqualTo("CREDIT");
    }

    @Test
    void split_cash_plus_card_sums_to_paid_and_marks_split() {
        SettleResult r = PaymentService.settle(new BigDecimal("1670.00"),
                List.of(tender("CASH", "500.00"), tender("CARD", "1170.00")));
        assertThat(r.paid()).isEqualByComparingTo("1670.00");
        assertThat(r.due()).isEqualByComparingTo("0.00");
        assertThat(r.tendered()).isEqualByComparingTo("1670.00");
        assertThat(r.paymentMode()).isEqualTo("SPLIT");
    }

    @Test
    void partial_payment_leaves_the_remainder_due() {
        SettleResult r = PaymentService.settle(new BigDecimal("200.00"), List.of(tender("CASH", "50.00")));
        assertThat(r.paid()).isEqualByComparingTo("50.00");
        assertThat(r.due()).isEqualByComparingTo("150.00");
    }

    @Test
    void no_tenders_is_fully_due_with_null_mode() {
        SettleResult r = PaymentService.settle(new BigDecimal("100.00"), List.of());
        assertThat(r.paid()).isEqualByComparingTo("0.00");
        assertThat(r.due()).isEqualByComparingTo("100.00");
        assertThat(r.paymentMode()).isNull();
    }

    @Test
    void unknown_method_falls_back_to_cash() {
        SettleResult r = PaymentService.settle(new BigDecimal("10.00"), List.of(tender("bogus", "10.00")));
        assertThat(r.paid()).isEqualByComparingTo("10.00");   // treated as a non-credit (CASH) tender
        assertThat(r.paymentMode()).isEqualTo("CASH");
    }
}
