package com.myplus.common.installment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * INST-1 — the arithmetic that decides what a customer owes and when.
 *
 * <p>Pure logic, no Spring and no Docker, so these run on <b>every</b> {@code mvn test}. That is the point:
 * standard D2a records that a skipped Testcontainers test is indistinguishable from a passing one, and 13 of
 * business-service's container tests were silently skipping while covering its riskiest code. Money arithmetic
 * must not be able to hide like that.
 */
class ScheduleGeneratorTest {

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    /** A realistic counter sale: a 60,000 handset, 15,000 down, six monthly payments. */
    private static PlanTerms sixMonthHandset() {
        return PlanTerms.of(money("60000.00"), money("15000.00"), 6,
                Frequency.MONTHLY, LocalDate.of(2026, 9, 15));
    }

    @Nested
    @DisplayName("the parts sum to the whole")
    class SumsExactly {

        @Test
        @DisplayName("a clean division: 45,000 over 6 is six equal payments")
        void cleanDivision() {
            List<ScheduledAmount> s = ScheduleGenerator.generate(sixMonthHandset());

            assertEquals(6, s.size());
            for (ScheduledAmount a : s) assertEquals(money("7500.00"), a.amount());
            assertEquals(money("45000.00"), ScheduleGenerator.total(s));
        }

        @Test
        @DisplayName("an UNclean division puts the remainder on the LAST installment — 10,000 over 3")
        void remainderOnLast() {
            // The worked example from the design doc. 10,000/3 has no exact 2-decimal form, and a shop's
            // ledger must not be out by a paisa.
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("10000.00"), BigDecimal.ZERO, 3,
                            Frequency.MONTHLY, LocalDate.of(2026, 9, 1)));

            assertEquals(money("3333.33"), s.get(0).amount());
            assertEquals(money("3333.33"), s.get(1).amount());
            assertEquals(money("3333.34"), s.get(2).amount());
            assertEquals(money("10000.00"), ScheduleGenerator.total(s));
        }

        @ParameterizedTest
        @CsvSource({
                "10000.00, 0.00,     3",
                "10000.00, 1234.56,  7",
                "99999.99, 0.01,    11",
                "45000.00, 15000.00, 6",
                "1.00,     0.00,      3",
                "17.70,    0.00,     60",
                "123456.78, 6789.01, 13",
        })
        @DisplayName("whatever the amounts, the schedule reconciles to the financed amount exactly")
        void alwaysReconciles(String price, String down, int count) {
            PlanTerms terms = PlanTerms.of(money(price), money(down), count,
                    Frequency.MONTHLY, LocalDate.of(2026, 1, 15));

            List<ScheduledAmount> s = ScheduleGenerator.generate(terms);

            assertEquals(count, s.size());
            assertEquals(terms.financedAmount(), ScheduleGenerator.total(s));
        }

        @Test
        @DisplayName("every amount is at 2 decimal places — a money column must never receive a scale-0 value")
        void alwaysMoneyScale() {
            for (ScheduledAmount a : ScheduleGenerator.generate(sixMonthHandset())) {
                assertEquals(ScheduleGenerator.MONEY_SCALE, a.amount().scale());
            }
        }
    }

    @Nested
    @DisplayName("no installment is ever worthless")
    class NeverZero {

        @Test
        @DisplayName("17.70 over 60 — the case HALF_UP would end with a 0.00 final payment")
        void theHalfUpTrap() {
            // 17.70/60 = 0.295. HALF_UP gives 0.30, and 59 x 0.30 is already the whole 17.70, so the last
            // installment would compute as 0.00 — a customer asked for nothing on the final date, and a
            // reminder texting them about it. Rounding DOWN makes each part a floor, so the remainder is
            // always at least as big as the others.
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("17.70"), BigDecimal.ZERO, 60,
                            Frequency.MONTHLY, LocalDate.of(2026, 1, 1)));

            assertEquals(money("0.29"), s.get(0).amount());
            assertEquals(money("0.59"), s.get(59).amount());
            assertEquals(money("17.70"), ScheduleGenerator.total(s));
        }

        @ParameterizedTest
        @CsvSource({ "10000.00, 3", "17.70, 60", "1.00, 3", "0.06, 6", "99999.99, 24" })
        @DisplayName("across the awkward cases, every single installment is positive")
        void everyInstallmentPositive(String price, int count) {
            for (ScheduledAmount a : ScheduleGenerator.generate(
                    PlanTerms.of(money(price), BigDecimal.ZERO, count,
                            Frequency.MONTHLY, LocalDate.of(2026, 1, 1)))) {
                assertTrue(a.amount().signum() > 0, "installment " + a.seqNo() + " was " + a.amount());
            }
        }

        @Test
        @DisplayName("too little to split is refused, not rounded into rows of 0.00")
        void refusesUnsplittable() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                    ScheduleGenerator.generate(PlanTerms.of(money("0.05"), BigDecimal.ZERO, 6,
                            Frequency.MONTHLY, LocalDate.of(2026, 1, 1))));
            assertTrue(e.getMessage().contains("6"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("due dates follow the calendar, not a 30-day counter")
    class DueDates {

        @Test
        @DisplayName("MONTHLY from the 31st clamps to February and RETURNS to the 31st in March")
        void monthEndDoesNotDecay() {
            // Measured from the FIRST due date, never by advancing the previous one. Iterating would give
            // 31 Jan -> 28 Feb -> 28 Mar -> 28 Apr: the day of month decays to the shortest month the plan
            // ever passed through, and every date after a February is wrong.
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("3000.00"), BigDecimal.ZERO, 4,
                            Frequency.MONTHLY, LocalDate.of(2026, 1, 31)));

            assertEquals(LocalDate.of(2026, 1, 31), s.get(0).dueDate());
            assertEquals(LocalDate.of(2026, 2, 28), s.get(1).dueDate());
            assertEquals(LocalDate.of(2026, 3, 31), s.get(2).dueDate());
            assertEquals(LocalDate.of(2026, 4, 30), s.get(3).dueDate());
        }

        @Test
        @DisplayName("a leap February is respected")
        void leapYear() {
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("2000.00"), BigDecimal.ZERO, 2,
                            Frequency.MONTHLY, LocalDate.of(2028, 1, 31)));
            assertEquals(LocalDate.of(2028, 2, 29), s.get(1).dueDate());
        }

        @Test
        @DisplayName("WEEKLY and FORTNIGHTLY step by 7 and 14 days")
        void weeklyAndFortnightly() {
            LocalDate start = LocalDate.of(2026, 9, 1);

            List<ScheduledAmount> weekly = ScheduleGenerator.generate(
                    PlanTerms.of(money("3000.00"), BigDecimal.ZERO, 3, Frequency.WEEKLY, start));
            assertEquals(LocalDate.of(2026, 9, 8), weekly.get(1).dueDate());
            assertEquals(LocalDate.of(2026, 9, 15), weekly.get(2).dueDate());

            List<ScheduledAmount> fortnightly = ScheduleGenerator.generate(
                    PlanTerms.of(money("3000.00"), BigDecimal.ZERO, 3, Frequency.FORTNIGHTLY, start));
            assertEquals(LocalDate.of(2026, 9, 15), fortnightly.get(1).dueDate());
            assertEquals(LocalDate.of(2026, 9, 29), fortnightly.get(2).dueDate());
        }

        @Test
        @DisplayName("the first installment falls on the date chosen, not one period after it")
        void firstIsTheChosenDate() {
            assertEquals(LocalDate.of(2026, 9, 15), ScheduleGenerator.generate(sixMonthHandset()).get(0).dueDate());
        }

        @Test
        @DisplayName("a back-dated plan is allowed — a shop entering its existing paper book")
        void backDatedIsAllowed() {
            // This library holds no clock at all, so "in the past" is not something it can or should judge.
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("6000.00"), BigDecimal.ZERO, 3,
                            Frequency.MONTHLY, LocalDate.of(2019, 3, 10)));
            assertEquals(LocalDate.of(2019, 3, 10), s.get(0).dueDate());
        }

        @Test
        @DisplayName("finalDueDate reports when the plan finishes")
        void finalDueDate() {
            assertEquals(LocalDate.of(2027, 2, 15),
                    ScheduleGenerator.finalDueDate(ScheduleGenerator.generate(sixMonthHandset())));
            assertNull(ScheduleGenerator.finalDueDate(List.of()));
            assertNull(ScheduleGenerator.finalDueDate(null));
        }
    }

    @Nested
    @DisplayName("sequence numbers")
    class SeqNos {

        @Test
        @DisplayName("1-based and contiguous — a customer is told \"3 of 6\"")
        void oneBasedContiguous() {
            List<ScheduledAmount> s = ScheduleGenerator.generate(sixMonthHandset());
            for (int i = 0; i < s.size(); i++) assertEquals(i + 1, s.get(i).seqNo());
        }
    }

    @Nested
    @DisplayName("terms that cannot make a sound plan are refused")
    class Refusals {

        private String refusalFor(PlanTerms terms) {
            return assertThrows(IllegalArgumentException.class,
                    () -> ScheduleGenerator.generate(terms)).getMessage();
        }

        @Test
        @DisplayName("a single-installment plan is legitimate, not an error")
        void singleInstallmentIsFine() {
            List<ScheduledAmount> s = ScheduleGenerator.generate(
                    PlanTerms.of(money("5000.00"), money("1000.00"), 1,
                            Frequency.MONTHLY, LocalDate.of(2026, 10, 1)));
            assertEquals(1, s.size());
            assertEquals(money("4000.00"), s.get(0).amount());
        }

        @Test
        @DisplayName("zero or negative price")
        void badPrice() {
            assertTrue(refusalFor(PlanTerms.of(BigDecimal.ZERO, BigDecimal.ZERO, 3,
                    Frequency.MONTHLY, LocalDate.of(2026, 1, 1))).contains("price"));
        }

        @Test
        @DisplayName("a down payment larger than the price")
        void downPaymentTooLarge() {
            assertTrue(refusalFor(PlanTerms.of(money("1000.00"), money("2000.00"), 3,
                    Frequency.MONTHLY, LocalDate.of(2026, 1, 1))).contains("down payment"));
        }

        @Test
        @DisplayName("a down payment covering the whole price leaves nothing to finance")
        void nothingLeftToFinance() {
            assertTrue(refusalFor(PlanTerms.of(money("1000.00"), money("1000.00"), 3,
                    Frequency.MONTHLY, LocalDate.of(2026, 1, 1))).contains("Nothing is left to finance"));
        }

        @Test
        @DisplayName("fewer than one installment")
        void badCount() {
            assertTrue(refusalFor(PlanTerms.of(money("1000.00"), BigDecimal.ZERO, 0,
                    Frequency.MONTHLY, LocalDate.of(2026, 1, 1))).contains("at least one"));
        }

        @Test
        @DisplayName("a missing frequency or first due date")
        void missingSchedule() {
            assertTrue(refusalFor(new PlanTerms(money("1000.00"), BigDecimal.ZERO, 3,
                    null, LocalDate.of(2026, 1, 1), BigDecimal.ZERO)).contains("how often"));
            assertTrue(refusalFor(new PlanTerms(money("1000.00"), BigDecimal.ZERO, 3,
                    Frequency.MONTHLY, null, BigDecimal.ZERO)).contains("first due date"));
        }

        @Test
        @DisplayName("markup is REFUSED, not silently ignored, until INST-6")
        void markupRefusedNotDropped() {
            // A number accepted and quietly dropped is how a shop discovers months later that it financed at
            // cost. Correct treatment needs a 4400 Finance Income account and the five gl_outbox copy points
            // — the exact change shape that once left 4200 Sales Discount empty in every tenant.
            String msg = refusalFor(new PlanTerms(money("60000.00"), money("15000.00"), 6,
                    Frequency.MONTHLY, LocalDate.of(2026, 9, 15), money("5000.00")));
            assertTrue(msg.contains("Markup is not supported yet"), msg);
        }

        @Test
        @DisplayName("null terms")
        void nullTerms() {
            assertTrue(refusalFor(null).contains("No installment terms"));
        }
    }

    @Nested
    @DisplayName("total()")
    class Total {

        @Test
        @DisplayName("is null-safe and scaled, so a caller can compare it to a money column directly")
        void nullSafe() {
            assertEquals(money("0.00"), ScheduleGenerator.total(null));
            assertEquals(money("0.00"), ScheduleGenerator.total(List.of()));
            assertEquals(ScheduleGenerator.MONEY_SCALE, ScheduleGenerator.total(null).scale());
        }
    }

    @Nested
    @DisplayName("money the client simply did not send")
    class AbsentMoney {

        /**
         * THE GAP THE FIRST GATE RUN FOUND, and the reason it is worth a nested class of its own.
         *
         * <p>Every other case in this file constructs terms with explicit {@code BigDecimal}s. None of them
         * exercised what a real client actually sends: JSON with no {@code markupAmount} key at all, which
         * deserialises to {@code null}. That null travelled through the DTO, through {@code PlanTerms}, and
         * into an INSERT against a {@code NOT NULL} column — so the plan died <b>after the sale had already
         * committed</b>, and the caller's transaction came back "marked as rollback-only".
         *
         * <p>A test that only ever builds objects the way its author would is testing the author.
         */
        @Test
        @DisplayName("absent markup and down payment become zero, not null")
        void absentBecomesZero() {
            PlanTerms t = new PlanTerms(money("60000.00"), null, 6,
                    Frequency.MONTHLY, LocalDate.of(2026, 9, 15), null);

            assertEquals(0, t.markupAmount().signum());
            assertEquals(0, t.downPayment().signum());
            assertNull(t.validate(), "absent markup is not a markup");
            assertEquals(money("60000.00"), t.financedAmount());
            assertEquals(money("60000.00"), ScheduleGenerator.total(ScheduleGenerator.generate(t)));
        }

        @Test
        @DisplayName("a MISSING price is still refused by name, not silently zeroed")
        void missingPriceStillRefused() {
            // Normalising absent money must not turn a missing price into a plan worth nothing.
            PlanTerms t = new PlanTerms(null, null, 6,
                    Frequency.MONTHLY, LocalDate.of(2026, 9, 15), null);

            assertTrue(t.validate().contains("more than zero"), t.validate());
        }
    }
}
