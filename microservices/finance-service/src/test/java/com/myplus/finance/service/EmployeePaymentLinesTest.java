package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.entity.PartyType;

/** EX-7a (F6) — paying a member back for an approved claim clears 2300, never 2000; its reversal is the exact opposite. */
class EmployeePaymentLinesTest {

    private static final BigDecimal X = new BigDecimal("35.00");

    private static String side(List<JournalLineDTO> lines, String code) {
        for (JournalLineDTO l : lines) {
            if (!code.equals(l.getAccountCode())) continue;
            if (l.getDebit() != null && l.getDebit().signum() > 0) return "Dr " + l.getDebit();
            if (l.getCredit() != null && l.getCredit().signum() > 0) return "Cr " + l.getCredit();
        }
        return "-";
    }

    @Test
    @DisplayName("⭐ a member paid back in cash: Dr 2300 / Cr 1000 — Accounts Payable untouched")
    void employeeCash() {
        List<JournalLineDTO> l = PostingService.paymentLines("DISBURSEMENT", X, "CASH", PartyType.EMPLOYEE);
        assertThat(side(l, "2300")).isEqualTo("Dr 35.00");
        assertThat(side(l, "1000")).isEqualTo("Cr 35.00");
        assertThat(side(l, "2000")).isEqualTo("-");
    }

    @Test
    @DisplayName("by bank: Cr 1010; the reversal mirrors it exactly")
    void employeeBankAndReversal() {
        List<JournalLineDTO> pay = PostingService.paymentLines("DISBURSEMENT", X, "BANK", PartyType.EMPLOYEE);
        List<JournalLineDTO> rev = PostingService.paymentReversalLines("DISBURSEMENT", X, "BANK", PartyType.EMPLOYEE);
        assertThat(side(pay, "1010")).isEqualTo("Cr 35.00");
        assertThat(side(rev, "1010")).isEqualTo("Dr 35.00");
        assertThat(side(rev, "2300")).isEqualTo("Cr 35.00");
    }

    @Test
    @DisplayName("a supplier (or no party, every caller before EX-7a) still settles 2000 — unchanged")
    void vendorUnchanged() {
        assertThat(side(PostingService.paymentLines("DISBURSEMENT", X, "CASH", PartyType.VENDOR), "2000")).isEqualTo("Dr 35.00");
        assertThat(side(PostingService.paymentLines("DISBURSEMENT", X, "CASH"), "2000")).isEqualTo("Dr 35.00");
        assertThat(side(PostingService.paymentLines("RECEIPT", X, "CASH", PartyType.CUSTOMER), "1100")).isEqualTo("Cr 35.00");
    }

    @Test
    @DisplayName("money received FROM a member is refused until advances exist (EX-7b) — never credited to customers")
    void employeeReceiptRefused() {
        assertThatThrownBy(() -> PostingService.paymentLines("RECEIPT", X, "CASH", PartyType.EMPLOYEE))
                .hasMessageContaining("not supported yet");
    }
}
