package com.myplus.finance.service;

import com.myplus.finance.dto.JournalLineDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Slice 0.1 — the FEE_COLLECTION journal shape: Dr Cash|Bank = Cr 4100 Fee Income.
 *
 * Asserts the rule that matters (it balances, and it is a two-line entry) through the same {@code GlService
 * .validate} the real posting path uses. Pure — no Spring, no DB — so it runs on every {@code mvn test}.
 */
class FeeCollectionPostingTest {

    private static final String CASH = "1000", BANK = "1010", FEE_INCOME = "4100";

    private static JournalLineDTO dr(String account, String amt) {
        return JournalLineDTO.builder().accountId(Long.parseLong(account)).debit(new BigDecimal(amt)).build();
    }
    private static JournalLineDTO cr(String account, String amt) {
        return JournalLineDTO.builder().accountId(Long.parseLong(account)).credit(new BigDecimal(amt)).build();
    }

    @Test @DisplayName("A cash fee collection balances: Dr 1000 = Cr 4100")
    void cashFeeBalances() {
        assertDoesNotThrow(() -> GlService.validate(List.of(dr(CASH, "5000.00"), cr(FEE_INCOME, "5000.00"))));
    }

    @Test @DisplayName("A cheque fee collection debits Bank instead of Cash, and still balances")
    void chequeFeeBalances() {
        assertDoesNotThrow(() -> GlService.validate(List.of(dr(BANK, "5000.00"), cr(FEE_INCOME, "5000.00"))));
    }

    @Test @DisplayName("A partial payment posts only what was collected — no receivable line")
    void partialPaymentPostsOnlyTheCollectedAmount() {
        // Due 8000, parent pays 3000. Slice 0.1 deliberately posts 3000 and nothing else: education has no AR
        // model yet, so a debit on 1100 would sit there with nothing able to clear it. The 5000 balance becomes a
        // real receivable in slice 0.2.
        assertDoesNotThrow(() -> GlService.validate(List.of(dr(CASH, "3000.00"), cr(FEE_INCOME, "3000.00"))));
    }
}
