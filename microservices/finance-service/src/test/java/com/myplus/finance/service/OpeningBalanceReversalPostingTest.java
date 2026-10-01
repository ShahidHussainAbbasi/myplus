package com.myplus.finance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;

/**
 * L18 — reversing an opening balance posts the exact mirror of what the opening balance posted.
 *
 * <p>Asserts the SIGNED movement per account, not merely that the reversal balances on its own: a reversal
 * that debited 1100 again would balance perfectly and double the phantom receivable it exists to remove.
 */
class OpeningBalanceReversalPostingTest {

    /** Net movement per account (debit positive, credit negative). */
    private static Map<String, BigDecimal> net(List<JournalLineDTO> lines) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JournalLineDTO l : lines) {
            BigDecimal d = l.getDebit() == null ? BigDecimal.ZERO : l.getDebit();
            BigDecimal c = l.getCredit() == null ? BigDecimal.ZERO : l.getCredit();
            m.merge(l.getAccountCode(), d.subtract(c), BigDecimal::add);
        }
        return m;
    }

    @Test @DisplayName("The reversal credits 1100 AR and debits 3000 Equity")
    void reversalDirection() {
        Map<String, BigDecimal> m = net(PostingService.openingReceivableReversalLines(new BigDecimal("45000.00")));
        assertEquals(new BigDecimal("-45000.00"), m.get("1100"), "AR is credited");
        assertEquals(new BigDecimal("45000.00"), m.get("3000"), "Owner's Equity is debited");
        assertEquals(2, m.size(), "no other account moves — no Sales, no tax");
    }

    @Test @DisplayName("Opening balance + its reversal net every account to zero")
    void pairNetsToZero() {
        BigDecimal owed = new BigDecimal("675000.00");
        List<JournalLineDTO> both = new java.util.ArrayList<>(PostingService.openingReceivableLines(owed));
        both.addAll(PostingService.openingReceivableReversalLines(owed));
        net(both).forEach((acct, v) -> assertEquals(0, v.signum(), "account " + acct + " nets to zero"));
    }
}
