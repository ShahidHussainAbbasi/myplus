package com.myplus.common.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.myplus.common.credit.CreditLimitPolicy.Action;
import com.myplus.common.credit.CreditLimitPolicy.Verdict;

/**
 * B2B Phase 1 — the credit-limit arithmetic and the policy decision.
 *
 * <p>Pure logic, no Spring, so it runs on every {@code mvn test}. This is where the real risk of the slice
 * lives: two of these cases (an edit double-counting, and store credit inflating exposure) would each ship a
 * guard that fires on the wrong transactions, and both are invisible from the UI until a shopkeeper is
 * arguing with a customer about it.
 */
class CreditLimitPolicyTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("evaluate — the projection")
    class Evaluate {

        @Test
        @DisplayName("NO limit can never breach, however large the balance")
        void nullLimitNeverBreaches() {
            // The back-compat guarantee: every existing customer has a null limit, so nothing changes for them.
            Verdict v = CreditLimitPolicy.evaluate(bd("999999"), bd("50000"), null, null);
            assertFalse(v.breached());
            assertNull(v.limit());
            assertEquals(0, v.over().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("exactly AT the limit is within it; one paisa over breaches")
        void boundary() {
            assertFalse(CreditLimitPolicy.evaluate(bd("600"), bd("400"), null, bd("1000")).breached(),
                    "1000 against a 1000 limit is allowed — a limit is the most they MAY owe");
            Verdict over = CreditLimitPolicy.evaluate(bd("600"), bd("400.01"), null, bd("1000"));
            assertTrue(over.breached());
            assertEquals(0, over.over().compareTo(bd("0.01")), "reports how far over");
        }

        @Test
        @DisplayName("a fully-paid sale adds no exposure, even for a customer already over")
        void fullyPaidNeverBreaches() {
            // Cash from someone deep in debt must not be refused — it improves the position.
            Verdict v = CreditLimitPolicy.evaluate(bd("5000"), BigDecimal.ZERO, null, bd("1000"));
            assertTrue(v.breached(), "they ARE over — but from the existing balance, not this sale");
            assertEquals(0, v.exposure().compareTo(bd("5000")), "this sale added nothing");
        }

        @Test
        @DisplayName("an overpayment never ADDS exposure")
        void negativeUnpaidIsClamped() {
            Verdict v = CreditLimitPolicy.evaluate(bd("500"), bd("-200"), null, bd("1000"));
            assertEquals(0, v.exposure().compareTo(bd("500")), "paying extra does not raise what they owe");
            assertFalse(v.breached());
        }

        @Test
        @DisplayName("EDITING does not double-count the invoice being edited (2g.1)")
        void editDoesNotDoubleCount() {
            // Customer owes 900, of which THIS invoice contributes 400. It is being reduced to 100.
            // Naive maths: 900 + 100 = 1000 → breach against a 950 limit. Correct: 900 + 100 − 400 = 600.
            Verdict v = CreditLimitPolicy.evaluate(bd("900"), bd("100"), bd("400"), bd("950"));
            assertEquals(0, v.exposure().compareTo(bd("600")));
            assertFalse(v.breached(), "reducing an invoice must never trigger the guard");
        }

        @Test
        @DisplayName("editing an over-limit invoice UPWARD still breaches")
        void editUpwardStillBreaches() {
            // The subtraction must not become a blanket exemption for edits.
            Verdict v = CreditLimitPolicy.evaluate(bd("900"), bd("800"), bd("400"), bd("950"));
            assertEquals(0, v.exposure().compareTo(bd("1300")));
            assertTrue(v.breached());
        }

        @Test
        @DisplayName("store credit is already out of thisUnpaid, so it reduces exposure (2g.2)")
        void storeCreditReducesExposure() {
            // Caller passes grandTotal − paid − storeCreditApplied. 500 bill, 300 redeemed → 200 unpaid.
            Verdict v = CreditLimitPolicy.evaluate(bd("800"), bd("200"), null, bd("1000"));
            assertFalse(v.breached(), "redeeming credit must not trip a limit while REDUCING what is owed");
        }

        @Test
        @DisplayName("exposure never goes negative")
        void exposureFloor() {
            Verdict v = CreditLimitPolicy.evaluate(bd("100"), BigDecimal.ZERO, bd("500"), bd("1000"));
            assertEquals(0, v.exposure().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("nulls anywhere are treated as zero — no NPE on the checkout path")
        void nullsAreSafe() {
            Verdict v = CreditLimitPolicy.evaluate(null, null, null, bd("100"));
            assertFalse(v.breached());
            assertEquals(0, v.exposure().compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("a zero limit means strictly cash-only, and is not confused with 'no limit'")
        void zeroLimitIsRealNotAbsent() {
            assertTrue(CreditLimitPolicy.evaluate(BigDecimal.ZERO, bd("0.01"), null, BigDecimal.ZERO).breached());
            assertFalse(CreditLimitPolicy.evaluate(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO).breached());
        }
    }

    @Nested
    @DisplayName("decide — policy + acknowledgement")
    class Decide {

        private final Verdict breached = new Verdict(true, bd("1500"), bd("500"), bd("1000"));
        private final Verdict clean = new Verdict(false, bd("500"), BigDecimal.ZERO, bd("1000"));

        @Test
        @DisplayName("no breach always proceeds, under every policy")
        void cleanAlwaysProceeds() {
            for (String p : new String[] { "off", "warn", "block", null, "nonsense" }) {
                assertEquals(Action.PROCEED, CreditLimitPolicy.decide(clean, p, false), "policy=" + p);
            }
        }

        @Test
        @DisplayName("warn asks FIRST, then proceeds once acknowledged")
        void warnAsksThenProceeds() {
            assertEquals(Action.CONFIRM, CreditLimitPolicy.decide(breached, "warn", false));
            assertEquals(Action.PROCEED, CreditLimitPolicy.decide(breached, "warn", true));
        }

        @Test
        @DisplayName("block refuses, and an acknowledgement does NOT get past it")
        void blockIgnoresAcknowledgement() {
            // This is the entire difference between block and warn: nobody on the till can consent past block.
            assertEquals(Action.REFUSE, CreditLimitPolicy.decide(breached, "block", false));
            assertEquals(Action.REFUSE, CreditLimitPolicy.decide(breached, "block", true));
        }

        @Test
        @DisplayName("off proceeds even when breached")
        void offProceeds() {
            assertEquals(Action.PROCEED, CreditLimitPolicy.decide(breached, "off", false));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   ", "WARN", "Warn", "typo" })
        @DisplayName("an unset or unrecognised policy falls back to ASKING, never to silently allowing")
        void unknownPolicyFailsToAsking(String policy) {
            assertEquals(Action.CONFIRM, CreditLimitPolicy.decide(breached, policy, false),
                    "a misconfigured policy must not quietly disable the guard");
        }

        @Test
        @DisplayName("a null verdict proceeds — the caller had nothing to judge")
        void nullVerdict() {
            assertEquals(Action.PROCEED, CreditLimitPolicy.decide(null, "block", false));
        }
    }

    @Nested
    @DisplayName("dueDateFrom")
    class DueDate {

        @Test
        @DisplayName("net terms add days to the transaction date")
        void addsTerms() {
            assertEquals(LocalDate.of(2026, 3, 2),
                    CreditLimitPolicy.dueDateFrom(LocalDate.of(2026, 1, 31), 30));
        }

        @Test
        @DisplayName("no terms → null, so the caller keeps today's hand-entered due date")
        void noTerms() {
            assertNull(CreditLimitPolicy.dueDateFrom(LocalDate.of(2026, 1, 31), null));
            assertNull(CreditLimitPolicy.dueDateFrom(null, 30));
            assertNull(CreditLimitPolicy.dueDateFrom(LocalDate.of(2026, 1, 31), -5));
        }

        @Test
        @DisplayName("zero-day terms mean due today, not 'no terms'")
        void zeroDays() {
            LocalDate d = LocalDate.of(2026, 1, 31);
            assertEquals(d, CreditLimitPolicy.dueDateFrom(d, 0));
        }
    }
}
