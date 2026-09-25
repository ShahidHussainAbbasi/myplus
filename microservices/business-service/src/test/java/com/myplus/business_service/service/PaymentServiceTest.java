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

    /*
     * PAID-1: this case used to assert paid = 150.00 — the DEFECT, written down as a test. The shop keeps 100.00;
     * the other 50.00 went back to the customer. Recorded as paid, it made due +50 (hiding 50 of the customer's
     * other debt), a void refund 150 and a return refund the change again.
     */
    @Test
    void cash_overpayment_returns_change_and_the_shop_keeps_only_the_bill() {
        SettleResult r = PaymentService.settle(new BigDecimal("100.00"), List.of(tender("CASH", "150.00")));
        assertThat(r.paid()).as("what the shop keeps").isEqualByComparingTo("100.00");
        assertThat(r.due()).isEqualByComparingTo("0.00");
        assertThat(r.change()).isEqualByComparingTo("50.00");
        assertThat(r.tendered()).as("what was handed over — still printed on the receipt").isEqualByComparingTo("150.00");
    }

    @Test
    void INV_000054_272_handed_over_for_42_keeps_42_and_returns_230() {   // the reported invoice, org 15
        SettleResult r = PaymentService.settle(new BigDecimal("42.00"), List.of(tender("CASH", "272.00")));
        assertThat(r.paid()).isEqualByComparingTo("42.00");
        assertThat(r.change()).isEqualByComparingTo("230.00");
        assertThat(r.due()).isEqualByComparingTo("0.00");
    }

    @Test
    void split_cash_and_card_over_the_bill_keeps_the_bill_and_returns_the_excess() {
        SettleResult r = PaymentService.settle(new BigDecimal("1000.00"),
                List.of(tender("CARD", "600.00"), tender("CASH", "500.00")));
        assertThat(r.paid()).isEqualByComparingTo("1000.00");
        assertThat(r.change()).isEqualByComparingTo("100.00");
        assertThat(r.tendered()).isEqualByComparingTo("1100.00");
    }

    @Test
    void credit_with_a_part_cash_payment_is_never_capped_below_what_was_paid() {
        SettleResult r = PaymentService.settle(new BigDecimal("500.00"),
                List.of(tender("CASH", "200.00"), tender("CREDIT", "0")));
        assertThat(r.paid()).isEqualByComparingTo("200.00");
        assertThat(r.due()).isEqualByComparingTo("300.00");
        assertThat(r.change()).isEqualByComparingTo("0.00");
    }

    @Test
    void an_edit_settles_only_what_is_still_owed() {   // SagaSaleWriter passes remaining = grand − alreadyPaid
        SettleResult r = PaymentService.settle(new BigDecimal("30.00"), List.of(tender("CASH", "50.00")));
        assertThat(r.paid()).as("the 30 still owed, not the 50 handed over").isEqualByComparingTo("30.00");
        assertThat(r.change()).isEqualByComparingTo("20.00");
    }

    @Test
    void nothing_owed_keeps_nothing_and_returns_everything() {   // remaining floored at 0 by the writer
        SettleResult r = PaymentService.settle(new BigDecimal("0.00"), List.of(tender("CASH", "20.00")));
        assertThat(r.paid()).isEqualByComparingTo("0.00");
        assertThat(r.change()).isEqualByComparingTo("20.00");
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

    @Test
    void insurance_plus_copay_settles_the_dispense_with_no_due() {   // P12 (slice 59)
        SettleResult r = PaymentService.settle(new BigDecimal("100.00"),
                List.of(tender("INSURANCE", "80.00"), tender("CASH", "20.00")));
        assertThat(r.paid()).isEqualByComparingTo("100.00");   // insurance counts as paid (not credit)
        assertThat(r.due()).isEqualByComparingTo("0.00");
        assertThat(r.change()).isEqualByComparingTo("0.00");
        assertThat(r.paymentMode()).isEqualTo("SPLIT");
    }
}
