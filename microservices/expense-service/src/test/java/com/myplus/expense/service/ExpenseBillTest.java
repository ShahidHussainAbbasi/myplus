package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.commerce.contracts.dto.PayableSnapshot;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.commerce.contracts.dto.PostingLine;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseBillPayment;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;

/** FP-3 — a bill's journal, its subledger snapshot, its payment request, and the rules its entity enforces. */
class ExpenseBillTest {

    private static ExpenseVoucher bill(String amount) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setId(77L);
        v.setOrganizationId(6L);
        v.setVoucherDate(LocalDate.of(2026, 10, 2));
        v.setPaidFrom("AP");
        v.setSupplierId(42L);
        v.setSupplierName("K-Electric");
        v.setDueDate(LocalDate.of(2026, 10, 30));
        v.setVersion(3);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(1L);
        l.setAccountCode("6300");
        l.setAmount(new BigDecimal(amount));
        v.addLine(l);
        v.post("EXP-000009", LocalDateTime.now());
        return v;
    }

    @Test @DisplayName("a bill posts Dr expense / Cr 2000 Accounts Payable, balanced")
    void journal() {
        PostingEventRequest r = VoucherPostings.post(bill("500.00"));
        Map<String, BigDecimal> net = new HashMap<>();
        for (PostingLine l : r.getLines()) {
            BigDecimal d = l.getDebit() == null ? BigDecimal.ZERO : l.getDebit();
            BigDecimal c = l.getCredit() == null ? BigDecimal.ZERO : l.getCredit();
            net.merge(l.getAccountCode(), d.subtract(c), BigDecimal::add);
        }
        assertThat(net.get("6300")).isEqualByComparingTo("500");
        assertThat(net.get("2000")).isEqualByComparingTo("-500");
        assertThat(net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
    }

    @Test @DisplayName("the subledger snapshot: EXPENSE_BILL keyed by voucher id, VENDOR party, versioned by @Version")
    void snapshot() {
        ExpenseVoucher v = bill("500.00");
        v.applyPayment(new BigDecimal("200.00"));
        PayableSnapshot s = VoucherPostings.payable(v);
        assertThat(s.getSource()).isEqualTo("EXPENSE_BILL");
        assertThat(s.getSourceRef()).isEqualTo("77");
        assertThat(s.getSourceVersion()).isEqualTo(3L);
        assertThat(s.getPartyType()).isEqualTo("VENDOR");
        assertThat(s.getPartyId()).isEqualTo(42L);
        assertThat(s.getAmount()).isEqualByComparingTo("500");
        assertThat(s.getPaid()).isEqualByComparingTo("200");
        assertThat(s.isVoided()).isFalse();
        assertThat(s.getIssuedAmount()).as("FP-4a: the bill as issued").isEqualByComparingTo("500");
        assertThat(s.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(s.getNotes()).as("FP-4a: a bill has no debit notes — empty, not 'not sent'").isEmpty();
    }

    @Test @DisplayName("paying: open goes down; more than is owed, or a non-bill, is refused")
    void payRules() {
        ExpenseVoucher v = bill("500.00");
        v.applyPayment(new BigDecimal("200.00"));
        assertThat(v.openAmount()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> v.applyPayment(new BigDecimal("300.01"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than is owed");
        v.applyPayment(new BigDecimal("300.00"));
        assertThat(v.openAmount()).isEqualByComparingTo("0");

        ExpenseVoucher cash = bill("100.00");
        cash.setPaidFrom("CASH");
        assertThatThrownBy(() -> cash.applyPayment(BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
        assertThat(cash.openAmount()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("a bill with a payment cannot be voided; an unpaid one can, and then owes nothing")
    void voidRules() {
        ExpenseVoucher paid = bill("500.00");
        paid.setPostingStatus(ExpenseVoucher.PS_POSTED_GL);
        paid.applyPayment(new BigDecimal("1.00"));
        assertThatThrownBy(() -> paid.voidWith("wrong", 1L, LocalDateTime.now())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reverse the payments first");

        ExpenseVoucher unpaid = bill("500.00");
        unpaid.setPostingStatus(ExpenseVoucher.PS_POSTED_GL);
        unpaid.voidWith("duplicate bill", 1L, LocalDateTime.now());
        assertThat(unpaid.voidNeedsReversal()).isTrue();
        assertThat(unpaid.openAmount()).isEqualByComparingTo("0");
        assertThat(VoucherPostings.payable(unpaid).isVoided()).isTrue();
    }

    @Test @DisplayName("the payment sent to finance: a DISBURSEMENT to the VENDOR, allocated to this bill, carrying the reference")
    void paymentRequest() {
        ExpenseVoucher v = bill("500.00");
        ExpenseBillPayment p = new ExpenseBillPayment();
        p.setAmount(new BigDecimal("200.00"));
        p.setMethod("BANK");
        p.setPaidOn(LocalDate.of(2026, 10, 3));
        p.setReference("EXPB-6-5");
        PaymentRecordRequest r = ExpenseBillService.request(p, v);
        assertThat(r.getDirection()).isEqualTo("DISBURSEMENT");
        assertThat(r.getPartyType()).isEqualTo("VENDOR");
        assertThat(r.getPartyId()).isEqualTo(42L);
        assertThat(r.getReference()).isEqualTo("EXPB-6-5");
        assertThat(r.getSourceModule()).isEqualTo("EXPENSE");
        assertThat(r.getAllocations()).singleElement().satisfies(a -> {
            assertThat(a.getDocType()).isEqualTo("EXPENSE_BILL");
            assertThat(a.getDocId()).isEqualTo(77L);
            assertThat(a.getAmount()).isEqualByComparingTo("200");
        });
    }

    @Test @DisplayName("a bill is paid by cash or bank only")
    void methods() {
        assertThat(ExpenseBillService.method(null)).isEqualTo("CASH");
        assertThat(ExpenseBillService.method("bank")).isEqualTo("BANK");
        assertThatThrownBy(() -> ExpenseBillService.method("AP")).isInstanceOf(ValidationException.class);
        assertThat(PaidFrom.of("ap").creditAccount()).isEqualTo("2000");
    }
}
