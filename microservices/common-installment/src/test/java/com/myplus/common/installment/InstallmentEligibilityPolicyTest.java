package com.myplus.common.installment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.myplus.common.installment.InstallmentEligibilityPolicy.CustomerStanding;
import com.myplus.common.installment.InstallmentEligibilityPolicy.Decision;
import com.myplus.common.installment.InstallmentEligibilityPolicy.Rules;

/**
 * INST-1 — may this customer take a plan at all?
 *
 * <p>Every rule here is inert until an owner switches it on, and that is the property most worth pinning: a shop
 * that enables installments and configures nothing else must behave exactly as it did before.
 */
class InstallmentEligibilityPolicyTest {

    private static final PlanTerms HANDSET = PlanTerms.of(
            new BigDecimal("60000.00"), new BigDecimal("15000.00"), 6,
            Frequency.MONTHLY, LocalDate.of(2026, 9, 15));

    private static CustomerStanding clean() {
        return new CustomerStanding(true, "35202-1234567-1", 0, 0);
    }

    private static Decision decide(CustomerStanding standing, Rules rules) {
        return InstallmentEligibilityPolicy.evaluate(standing, rules, HANDSET);
    }

    @Nested
    @DisplayName("inert by default")
    class InertByDefault {

        @Test
        @DisplayName("permissive rules allow a clean customer")
        void permissiveAllows() {
            Decision d = decide(clean(), Rules.permissive());
            assertTrue(d.allowed());
            assertNull(d.reason());
        }

        @Test
        @DisplayName("null rules behave as permissive — a caller that has not read settings yet cannot block a sale")
        void nullRulesArePermissive() {
            assertTrue(InstallmentEligibilityPolicy.evaluate(clean(), null, HANDSET).allowed());
        }

        @Test
        @DisplayName("with everything off, even a customer with plans and arrears is allowed")
        void offMeansOff() {
            assertTrue(decide(new CustomerStanding(true, null, 5, 120), Rules.permissive()).allowed());
        }
    }

    @Nested
    @DisplayName("a plan needs somebody to owe it")
    class MustBeIdentified {

        @Test
        @DisplayName("a walk-in is refused whatever the rules say")
        void walkInRefused() {
            Decision d = decide(new CustomerStanding(false, null, 0, 0), Rules.permissive());
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("Choose a customer"), d.reason());
        }

        @Test
        @DisplayName("a null standing is refused, never treated as fine")
        void nullStandingRefused() {
            assertFalse(InstallmentEligibilityPolicy.evaluate(null, Rules.permissive(), HANDSET).allowed());
        }
    }

    @Nested
    @DisplayName("CNIC")
    class Cnic {

        private final Rules requireCnic = new Rules(true,
                InstallmentEligibilityPolicy.UNLIMITED_PLANS,
                InstallmentEligibilityPolicy.OVERDUE_CHECK_OFF,
                InstallmentEligibilityPolicy.NO_MINIMUM_DOWN_PAYMENT);

        @Test
        @DisplayName("refused when missing")
        void refusedWhenMissing() {
            Decision d = decide(new CustomerStanding(true, null, 0, 0), requireCnic);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("CNIC"), d.reason());
        }

        @Test
        @DisplayName("a blank or whitespace CNIC is missing, not present")
        void blankIsMissing() {
            assertFalse(decide(new CustomerStanding(true, "", 0, 0), requireCnic).allowed());
            assertFalse(decide(new CustomerStanding(true, "   ", 0, 0), requireCnic).allowed());
        }

        @Test
        @DisplayName("allowed when on file")
        void allowedWhenPresent() {
            assertTrue(decide(clean(), requireCnic).allowed());
        }
    }

    @Nested
    @DisplayName("how many plans at once")
    class OpenPlanCap {

        private Rules max(int n) {
            return new Rules(false, n,
                    InstallmentEligibilityPolicy.OVERDUE_CHECK_OFF,
                    InstallmentEligibilityPolicy.NO_MINIMUM_DOWN_PAYMENT);
        }

        @Test
        @DisplayName("a cap of one refuses a second plan, and says so in the singular")
        void oneMeansOne() {
            assertTrue(decide(clean(), max(1)).allowed());

            Decision d = decide(new CustomerStanding(true, "x", 1, 0), max(1));
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("already has an installment plan"), d.reason());
        }

        @Test
        @DisplayName("a higher cap counts, and reports both numbers")
        void higherCap() {
            assertTrue(decide(new CustomerStanding(true, "x", 2, 0), max(3)).allowed());

            Decision d = decide(new CustomerStanding(true, "x", 3, 0), max(3));
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("3"), d.reason());
        }

        @Test
        @DisplayName("zero means unlimited, not \"no plans allowed\"")
        void zeroIsUnlimited() {
            // The trap in any cap setting: 0 read as a limit would refuse every plan in every shop that left
            // the field empty.
            assertTrue(decide(new CustomerStanding(true, "x", 9, 0), max(InstallmentEligibilityPolicy.UNLIMITED_PLANS))
                    .allowed());
        }
    }

    @Nested
    @DisplayName("existing arrears")
    class Arrears {

        private Rules blockAfter(int days) {
            return new Rules(false, InstallmentEligibilityPolicy.UNLIMITED_PLANS, days,
                    InstallmentEligibilityPolicy.NO_MINIMUM_DOWN_PAYMENT);
        }

        @Test
        @DisplayName("refused at or past the threshold, allowed below it")
        void thresholdIsInclusive() {
            assertTrue(decide(new CustomerStanding(true, "x", 0, 29), blockAfter(30)).allowed());

            Decision d = decide(new CustomerStanding(true, "x", 0, 30), blockAfter(30));
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("30 days late"), d.reason());
        }

        @Test
        @DisplayName("zero days means the check is off, not \"block on any lateness\"")
        void zeroIsOff() {
            assertTrue(decide(new CustomerStanding(true, "x", 0, 90),
                    blockAfter(InstallmentEligibilityPolicy.OVERDUE_CHECK_OFF)).allowed());
        }
    }

    @Nested
    @DisplayName("minimum down payment")
    class MinimumDownPayment {

        private Rules minPct(int pct) {
            return new Rules(false, InstallmentEligibilityPolicy.UNLIMITED_PLANS,
                    InstallmentEligibilityPolicy.OVERDUE_CHECK_OFF, pct);
        }

        @Test
        @DisplayName("25% of 60,000 is met by 15,000")
        void exactlyMet() {
            assertTrue(decide(clean(), minPct(25)).allowed());
        }

        @Test
        @DisplayName("30% of 60,000 is not met by 15,000, and the message names the amount required")
        void notMet() {
            Decision d = decide(clean(), minPct(30));
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("18000.00"), d.reason());
            assertTrue(d.reason().contains("30%"), d.reason());
        }

        @Test
        @DisplayName("the requirement rounds UP — a shortfall is never rounded away")
        void roundsUp() {
            // 30% of 1000.01 is 300.003. HALF_UP would call 300.00 sufficient; a policy that can be met by
            // less than it states has quietly stopped being a policy.
            PlanTerms odd = PlanTerms.of(new BigDecimal("1000.01"), new BigDecimal("300.00"), 6,
                    Frequency.MONTHLY, LocalDate.of(2026, 9, 15));
            Decision d = InstallmentEligibilityPolicy.evaluate(clean(), minPct(30), odd);
            assertFalse(d.allowed());
            assertTrue(d.reason().contains("300.01"), d.reason());
        }

        @Test
        @DisplayName("zero percent imposes no floor")
        void zeroPercentIsOff() {
            PlanTerms noDown = PlanTerms.of(new BigDecimal("60000.00"), BigDecimal.ZERO, 6,
                    Frequency.MONTHLY, LocalDate.of(2026, 9, 15));
            assertTrue(InstallmentEligibilityPolicy.evaluate(clean(),
                    minPct(InstallmentEligibilityPolicy.NO_MINIMUM_DOWN_PAYMENT), noDown).allowed());
        }
    }

    @Nested
    @DisplayName("the order rules are checked")
    class CheckOrder {

        @Test
        @DisplayName("identity is reported before configured rules — the most useful reason wins")
        void identityFirst() {
            // A walk-in with no CNIC breaks two rules. "Choose a customer" is the one the cashier can act on;
            // "no CNIC on file" would send them looking for a record that does not exist.
            Rules strict = new Rules(true, 1, 30, 50);
            Decision d = InstallmentEligibilityPolicy.evaluate(
                    new CustomerStanding(false, null, 4, 90), strict, HANDSET);
            assertTrue(d.reason().contains("Choose a customer"), d.reason());
        }
    }
}
