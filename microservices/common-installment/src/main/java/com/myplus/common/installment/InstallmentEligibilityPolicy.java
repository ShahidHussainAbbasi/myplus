package com.myplus.common.installment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * May this customer take a plan at all? — the questions a shop asks before it agrees to be owed money over
 * months, separate from the arithmetic of how that money is dated.
 *
 * <h3>No SPI, deliberately</h3>
 * Like {@code common-credit}'s {@code CreditLimitPolicy} — which this deliberately mirrors, and which is
 * referenced by name rather than imported so this library keeps its zero-dependency footprint — every caller
 * already holds these figures on rows it has just loaded. An interface to fetch data the caller already has
 * would be ceremony, not decoupling. That is also what keeps this class pure and testable with no context.
 *
 * <h3>What this does NOT do: the credit limit</h3>
 * An installment sale's exposure is the financed amount, and {@code CreditLimitPolicy.evaluate} already measures
 * exactly that — including the B2B shared pool, and including the edit case that must not double-count. Asking
 * the same question a second way here would eventually give a second answer, and the difference would only
 * surface in someone's ledger. The sell path keeps calling {@code assertCreditPolicy}; this runs beside it.
 *
 * <p>Every rule below is <b>inert until an owner switches it on</b>, so a shop that turns installments on and
 * configures nothing else behaves exactly as it did before.
 */
public final class InstallmentEligibilityPolicy {

    /** Off switches, stated once so a caller cannot invent a different sentinel. */
    public static final int UNLIMITED_PLANS = 0;
    public static final int OVERDUE_CHECK_OFF = 0;
    public static final int NO_MINIMUM_DOWN_PAYMENT = 0;

    private InstallmentEligibilityPolicy() {
    }

    /**
     * What the shop may do, and why.
     *
     * @param allowed true when nothing stands in the way
     * @param reason  null when allowed; otherwise a sentence for the cashier — never a code. The person reading
     *                it has to decide what to do next, and "ELIGIBILITY_FAILED" tells them nothing.
     */
    public record Decision(boolean allowed, String reason) {

        private static final Decision OK = new Decision(true, null);

        public static Decision allow() {
            return OK;
        }

        public static Decision refuse(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * The customer's standing, as the caller already knows it.
     *
     * @param identified       false for a walk-in. A plan is a debt, and a debt needs somebody to owe it.
     * @param cnic             the customer's national id, or null/blank when not captured
     * @param openPlanCount    plans already ACTIVE for this customer
     * @param worstOverdueDays how late the most overdue installment across their existing plans is; 0 or less
     *                         when nothing is overdue
     */
    public record CustomerStanding(boolean identified, String cnic, int openPlanCount, int worstOverdueDays) {
    }

    /**
     * The tenant's configured rules, read from {@code pos.installment.*}.
     *
     * @param requireCnic         refuse without a national id on file
     * @param maxOpenPlans        {@link #UNLIMITED_PLANS} for no cap
     * @param blockIfOverdueDays  {@link #OVERDUE_CHECK_OFF} to skip the check
     * @param minDownPaymentPct   0–100; {@link #NO_MINIMUM_DOWN_PAYMENT} for no floor
     */
    public record Rules(boolean requireCnic, int maxOpenPlans, int blockIfOverdueDays, int minDownPaymentPct) {

        /** Everything off — what a shop gets before an owner configures anything. */
        public static Rules permissive() {
            return new Rules(false, UNLIMITED_PLANS, OVERDUE_CHECK_OFF, NO_MINIMUM_DOWN_PAYMENT);
        }
    }

    /**
     * Decide, checking the cheapest and most fundamental things first so the cashier gets the most useful
     * reason rather than the first one that happens to fail.
     */
    public static Decision evaluate(CustomerStanding standing, Rules rules, PlanTerms terms) {
        if (standing == null || !standing.identified()) {
            return Decision.refuse("Choose a customer first — an installment plan has to belong to someone.");
        }
        Rules r = rules == null ? Rules.permissive() : rules;

        if (r.requireCnic() && isBlank(standing.cnic())) {
            return Decision.refuse("This customer has no CNIC on file, and one is required for installments.");
        }

        if (r.maxOpenPlans() != UNLIMITED_PLANS && standing.openPlanCount() >= r.maxOpenPlans()) {
            return Decision.refuse(r.maxOpenPlans() == 1
                    ? "This customer already has an installment plan running."
                    : "This customer already has " + standing.openPlanCount() + " installment plans running, and "
                            + r.maxOpenPlans() + " is the limit.");
        }

        if (r.blockIfOverdueDays() != OVERDUE_CHECK_OFF
                && standing.worstOverdueDays() >= r.blockIfOverdueDays()) {
            return Decision.refuse("This customer is " + standing.worstOverdueDays()
                    + " days late on an existing installment. Settle that before starting another plan.");
        }

        if (r.minDownPaymentPct() > NO_MINIMUM_DOWN_PAYMENT) {
            if (terms == null) return Decision.refuse("No installment terms were supplied.");
            BigDecimal price = nz(terms.cashPrice());
            if (price.signum() > 0) {
                BigDecimal required = price
                        .multiply(BigDecimal.valueOf(r.minDownPaymentPct()))
                        .divide(BigDecimal.valueOf(100), ScheduleGenerator.MONEY_SCALE, RoundingMode.UP);
                // UP, not HALF_UP: a 30% minimum met with 29.996% is not met. Rounding a shortfall away is how
                // a policy quietly stops being one.
                if (nz(terms.downPayment()).compareTo(required) < 0) {
                    return Decision.refuse("A down payment of at least " + required + " ("
                            + r.minDownPaymentPct() + "%) is required on this plan.");
                }
            }
        }

        return Decision.allow();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
