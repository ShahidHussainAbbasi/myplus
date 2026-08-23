package com.myplus.common.installment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.myplus.common.installment.RepossessionPolicy.Decision;
import com.myplus.common.installment.RepossessionPolicy.PlanStanding;
import com.myplus.common.installment.RepossessionPolicy.Rules;

/**
 * INST-5a — the repossession guards.
 *
 * <p>These run on {@code mvn test} with nothing installed and nothing running. That matters more here than
 * anywhere else in the feature: repossession is the one action in the installment code that can be <b>legally
 * wrong</b>, and a rule that protects a customer is worth very little if the test proving it can silently skip.
 */
class RepossessionPolicyTest {

    private static BigDecimal money(String v) { return new BigDecimal(v); }

    private static PlanStanding live(String cashPrice, String paid, int lateDays) {
        return new PlanStanding("ACTIVE", money(cashPrice), money(paid), lateDays);
    }

    /** Everything permitted — the baseline the guards are then added to, one at a time. */
    private static Rules permissive() {
        return new Rules(true, RepossessionPolicy.NO_MINIMUM_OVERDUE, RepossessionPolicy.PROTECTED_GOODS_OFF);
    }

    @Test
    @DisplayName("a shop that has not switched it on cannot repossess")
    void disabledByDefault() {
        Decision d = RepossessionPolicy.evaluate(live("60000", "10000", 90), Rules.off());
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("switched off"), d.reason());
    }

    @Test
    @DisplayName("a live, defaulted plan with the guards off is allowed")
    void allowedBaseline() {
        // The POSITIVE CONTROL for every refusal below. Without it each guard would be satisfied by a policy
        // that refuses everything, and the whole class would pass while the feature did nothing.
        assertTrue(RepossessionPolicy.evaluate(live("60000", "10000", 90), permissive()).allowed());
    }

    @Nested
    @DisplayName("only a live plan")
    class Status {

        @Test
        @DisplayName("a completed or cancelled plan is refused BY NAME")
        void notLive() {
            for (String s : new String[] { "COMPLETED", "CANCELLED", "WRITTEN_OFF" }) {
                Decision d = RepossessionPolicy.evaluate(
                        new PlanStanding(s, money("60000"), money("60000"), 0), permissive());
                assertFalse(d.allowed(), s);
                // The status is IN the message: "already CANCELLED" tells the shopkeeper the item was dealt
                // with, which is a different problem from being refused.
                assertTrue(d.reason().contains(s), d.reason());
            }
        }

        @Test
        @DisplayName("DEFAULTED is live — it is the state repossession exists for")
        void defaultedIsLive() {
            assertTrue(RepossessionPolicy.evaluate(
                    new PlanStanding("DEFAULTED", money("60000"), money("10000"), 90), permissive()).allowed());
        }
    }

    @Nested
    @DisplayName("minimum days overdue")
    class Lateness {

        private Rules after30() { return new Rules(true, 30, RepossessionPolicy.PROTECTED_GOODS_OFF); }

        @Test
        @DisplayName("a customer three days late keeps their phone")
        void tooEarly() {
            Decision d = RepossessionPolicy.evaluate(live("60000", "10000", 3), after30());
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("30"), d.reason());
        }

        @Test
        @DisplayName("nothing overdue at all says so, rather than quoting a threshold")
        void nothingLate() {
            Decision d = RepossessionPolicy.evaluate(live("60000", "10000", 0), after30());
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("Nothing is overdue"), d.reason());
        }

        @Test
        @DisplayName("⭐ the boundary: 29 days refused, 30 allowed")
        void boundary() {
            assertFalse(RepossessionPolicy.evaluate(live("60000", "10000", 29), after30()).allowed());
            assertTrue(RepossessionPolicy.evaluate(live("60000", "10000", 30), after30()).allowed());
        }
    }

    @Nested
    @DisplayName("protected goods")
    class ProtectedGoods {

        private Rules protectedAt66() { return new Rules(true, RepossessionPolicy.NO_MINIMUM_OVERDUE, 66); }

        @Test
        @DisplayName("⭐ two thirds paid puts the goods beyond reach")
        void protectedOnceMostlyPaid() {
            // 40,000 of 60,000 is 66.6% — over the line. Taking these goods anyway can void the debt entirely
            // and expose the shop to a claim for everything paid.
            Decision d = RepossessionPolicy.evaluate(live("60000", "40000", 200), protectedAt66());
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("court order"), d.reason());
            assertTrue(d.reason().contains("66"), d.reason());
        }

        @Test
        @DisplayName("just under the line is still recoverable")
        void belowThreshold() {
            // The negative control. 39,000/60,000 = 65% — without this, "protected" would be satisfied by a
            // rule that refuses every repossession regardless of what was paid.
            assertTrue(RepossessionPolicy.evaluate(live("60000", "39000", 200), protectedAt66()).allowed());
        }

        @Test
        @DisplayName("paying EXACTLY the threshold protects the customer")
        void inclusiveAtThreshold() {
            // >= not >. A rule that protects a customer should include its own boundary; the alternative is a
            // shop taking goods from someone who paid precisely the protected share.
            assertFalse(RepossessionPolicy.evaluate(live("60000", "39600", 200), protectedAt66()).allowed());
        }

        @Test
        @DisplayName("the share is measured against the CASH PRICE, so a big down payment counts")
        void measuredAgainstCashPrice() {
            // 30,000 down on a 60,000 handset, then 10,000 of instalments = 40,000 of the PRICE, i.e. 66%.
            // Measured against the FINANCED 30,000 instead, this same customer reads as 33% and would have
            // their phone taken — which is exactly the customer the rule exists to protect.
            assertFalse(RepossessionPolicy.evaluate(live("60000", "40000", 200), protectedAt66()).allowed());
        }

        @Test
        @DisplayName("0 turns the rule off entirely")
        void offMeansOff() {
            assertTrue(RepossessionPolicy.evaluate(live("60000", "59999", 200), permissive()).allowed());
        }
    }

    @Nested
    @DisplayName("paidPercent rounds DOWN, which is the protective direction")
    class Rounding {

        @Test
        void roundsDown() {
            // 65.9% must not become 66 and be refused when the threshold is 66.
            assertEquals(65, RepossessionPolicy.paidPercent(money("60000"), money("39550")));
            assertEquals(66, RepossessionPolicy.paidPercent(money("60000"), money("40000")));
        }

        @Test
        @DisplayName("a missing or zero price cannot make the rule fire on a meaningless number")
        void degenerate() {
            assertEquals(0, RepossessionPolicy.paidPercent(null, money("100")));
            assertEquals(0, RepossessionPolicy.paidPercent(money("0"), money("100")));
            assertEquals(0, RepossessionPolicy.paidPercent(money("60000"), null));
            assertEquals(0, RepossessionPolicy.paidPercent(money("60000"), money("0")));
        }
    }
}
