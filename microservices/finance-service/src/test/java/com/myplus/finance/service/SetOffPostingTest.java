package com.myplus.finance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;

/**
 * DR-4 — a set-off moves AR and AP and nothing else. Asserts the SIGNED movement per account: the defect this guards
 * against (F1) balanced perfectly — SETOFF fell through to 1000 Cash and booked money that never moved.
 */
class SetOffPostingTest {

    private static final BigDecimal X = new BigDecimal("30000.00");

    private static Map<String, BigDecimal> net(List<JournalLineDTO> lines) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JournalLineDTO l : lines) {
            BigDecimal d = l.getDebit() == null ? BigDecimal.ZERO : l.getDebit();
            BigDecimal c = l.getCredit() == null ? BigDecimal.ZERO : l.getCredit();
            m.merge(l.getAccountCode(), d.subtract(c), BigDecimal::add);
        }
        return m;
    }

    private static List<JournalLineDTO> bothLegs() {
        List<JournalLineDTO> all = new ArrayList<>(PostingService.paymentLines("RECEIPT", X, SetOffService.METHOD));
        all.addAll(PostingService.paymentLines("DISBURSEMENT", X, SetOffService.METHOD));
        return all;
    }

    @Test @DisplayName("SETOFF never reaches Cash or Bank: it goes to 1900 Set-off clearing")
    void setOffIsNotCash() {
        assertEquals("1900", PostingService.cashAccount("SETOFF"));
        assertEquals("1900", PostingService.cashAccount(" setoff "));
        // Everything else behaves exactly as before.
        assertEquals("1000", PostingService.cashAccount("CASH"));
        assertEquals("1010", PostingService.cashAccount("CARD"));
        assertEquals("1000", PostingService.cashAccount(null));
    }

    @Test @DisplayName("Both legs: AR −X, AP +X (debited), 1900 nets to zero, no cash, no bank")
    void bothLegsMoveArAndApOnly() {
        Map<String, BigDecimal> m = net(bothLegs());
        assertEquals(new BigDecimal("-30000.00"), m.get("1100"), "AR is credited");
        assertEquals(new BigDecimal("30000.00"), m.get("2000"), "AP is debited");
        assertEquals(0, m.get("1900").signum(), "clearing nets to zero");
        assertNull(m.get("1000"), "cash untouched");
        assertNull(m.get("1010"), "bank untouched");
    }

    @Test @DisplayName("A set-off and its reversal net every account to zero; the reversal alone nets 1900 too")
    void reversalUndoesEverything() {
        assertEquals(0, net(PostingService.setOffReversalLines(X)).get("1900").signum(), "1900 nets inside the reversal");
        List<JournalLineDTO> all = bothLegs();
        all.addAll(PostingService.setOffReversalLines(X));
        net(all).forEach((acct, v) -> assertEquals(0, v.signum(), "account " + acct + " nets to zero"));
    }
}
