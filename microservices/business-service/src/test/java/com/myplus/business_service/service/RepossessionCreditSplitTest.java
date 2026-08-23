package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INST-5a — the credit note's face value must BE the balance written off, to the paisa.
 *
 * <h3>This class exists because the first implementation shipped a real defect</h3>
 * It derived the credit by multiplying the invoice by a rounded fraction. Writing off 40,000 of a 60,000
 * invoice gives {@code 0.666667}, and {@code 60000 × 0.666667 = 40000.02} — so the customer was left
 * permanently two paisa in credit, with a residue on their statement and a phantom row on the aging report.
 *
 * <p>The trial balance <b>still balanced</b>, because the posting was self-consistent. Only the closing
 * balance caught it. That is the whole argument for asserting what the customer owes rather than that the
 * ledger is internally tidy.
 *
 * <p>These run on {@code mvn test} with nothing installed and nothing running — the rule is arithmetic, and
 * arithmetic should not need a database to prove.
 */
class RepossessionCreditSplitTest {

    private static BigDecimal m(String v) { return new BigDecimal(v); }

    /** net + tax, which must equal the amount written off exactly. */
    private static BigDecimal sum(BigDecimal[] split) { return split[0].add(split[1]); }

    @Test
    @DisplayName("⭐ the case that failed the gate: 40,000 of a 60,000 invoice, no tax")
    void theRegression() {
        BigDecimal[] s = RepossessionService.creditSplit(m("40000"), m("60000"), m("60000"), BigDecimal.ZERO);
        assertEquals(0, sum(s).compareTo(m("40000")), "credited " + sum(s));
        assertEquals(0, s[1].compareTo(BigDecimal.ZERO), "no tax on the invoice, none credited");
    }

    @Test
    @DisplayName("a price and part-payment that do NOT divide cleanly still reconcile")
    void awkwardArithmetic() {
        // 59,999 sold, 17,777 paid, so 42,222 is written off. Every one of these is deliberately prime-ish:
        // the original defect only surfaced because 40000/60000 happened to round badly, and a fixture that
        // divides cleanly would have hidden it.
        BigDecimal[] s = RepossessionService.creditSplit(m("42222"), m("59999"), m("59999"), BigDecimal.ZERO);
        assertEquals(0, sum(s).compareTo(m("42222")), "credited " + sum(s));
    }

    @Test
    @DisplayName("with tax, the parts STILL sum to the whole exactly")
    void withTax() {
        // 60,000 gross = 51,724.14 net + 8,275.86 tax at 16%. Write off 40,000 of it.
        BigDecimal[] s = RepossessionService.creditSplit(
                m("40000"), m("60000"), m("51724.14"), m("8275.86"));

        assertEquals(0, sum(s).compareTo(m("40000")), "net + tax must be the balance: " + sum(s));
        // Tax follows the RATE — it is filed with an authority and cannot absorb a rounding residue.
        assertEquals(0, s[1].compareTo(m("5517.24")), "tax credited: " + s[1]);
        // ...so net carries it.
        assertEquals(0, s[0].compareTo(m("34482.76")), "net credited: " + s[0]);
    }

    @Test
    @DisplayName("the residual lands on NET, never on tax")
    void residualOnNet() {
        // Chosen so the proportional tax rounds and leaves a residue to place.
        BigDecimal[] s = RepossessionService.creditSplit(
                m("33333.33"), m("59999.99"), m("50847.45"), m("9152.54"));
        assertEquals(0, sum(s).compareTo(m("33333.33")), "credited " + sum(s));
        assertTrue(s[1].scale() <= 2, "tax is money, to two places");
    }

    @Test
    @DisplayName("a full write-off credits the whole invoice and no more")
    void fullBalance() {
        BigDecimal[] s = RepossessionService.creditSplit(
                m("60000"), m("60000"), m("51724.14"), m("8275.86"));
        assertEquals(0, sum(s).compareTo(m("60000")));
        // Never more tax back than the invoice carried, whatever the arithmetic says.
        assertTrue(s[1].compareTo(m("8275.86")) <= 0, "tax credited: " + s[1]);
    }

    @Test
    @DisplayName("degenerate invoices cannot produce a nonsense credit")
    void degenerate() {
        // A zero-value invoice divides by nothing; the whole credit is net rather than an exception on a
        // counter with a customer standing at it.
        assertEquals(0, sum(RepossessionService.creditSplit(
                m("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).compareTo(m("100")));
        assertEquals(0, sum(RepossessionService.creditSplit(
                null, m("60000"), m("60000"), BigDecimal.ZERO)).compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("the property holds across a sweep, not just the cases someone thought of")
    void sweep() {
        // The defect survived review because the numbers chosen for the fixture divided badly by accident.
        // A sweep does not rely on anybody picking an unlucky pair.
        for (int gross = 1000; gross <= 100000; gross += 997) {
            BigDecimal g = BigDecimal.valueOf(gross);
            BigDecimal tax = g.multiply(m("0.16")).divide(m("1.16"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal net = g.subtract(tax);

            for (int paidPct = 0; paidPct < 100; paidPct += 7) {
                BigDecimal paid = g.multiply(BigDecimal.valueOf(paidPct))
                        .divide(m("100"), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal owed = g.subtract(paid);

                BigDecimal[] s = RepossessionService.creditSplit(owed, g, net, tax);
                assertEquals(0, sum(s).compareTo(owed),
                        "gross " + g + " owed " + owed + " credited " + sum(s));
                assertTrue(s[1].compareTo(tax) <= 0, "tax credited exceeds the invoice at gross " + g);
                assertTrue(s[0].signum() >= 0, "net credited went negative at gross " + g);
            }
        }
    }
}
