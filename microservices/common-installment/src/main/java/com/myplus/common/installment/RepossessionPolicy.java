package com.myplus.common.installment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * INST-5a — may this plan's item be taken back?
 *
 * <p>Pure, like {@link InstallmentEligibilityPolicy} beside it: no Spring, no repository, no clock. It is
 * handed the facts and returns a decision with a sentence a shopkeeper can act on. That is what lets the
 * boundaries below be ordinary unit tests rather than a Testcontainers run that <b>skips silently</b> on a
 * developer machine, where a skipped test looks exactly like a passing one.
 *
 * <h3>Repossession is the one action in this feature that can be legally wrong</h3>
 * Every other refusal in the installment code protects the shop. These protect the <b>customer</b>, and two of
 * them exist because taking goods back is regulated almost everywhere:
 *
 * <ul>
 *   <li><b>Minimum days overdue.</b> A customer three days late has not defaulted. Without a floor here the
 *       repossess button sits next to a name the moment a payment slips, and somebody eventually presses it.
 *   <li><b>Protected goods.</b> In many consumer-credit regimes goods become <i>protected</i> once a share of
 *       the price is paid — commonly two thirds — after which they cannot be recovered without a court order.
 *       Taking them anyway can void the debt entirely and expose the shop to a claim for everything paid.
 *       The threshold is a tenant setting because the share and the very existence of the rule vary by market;
 *       what does not vary is that a system offering repossession should be able to express it.
 * </ul>
 *
 * <p>The paid share is measured against the <b>cash price</b>, not the financed amount: a customer who put
 * 40% down and then paid a third of the balance has paid well over half of what the goods cost, and measuring
 * against the balance alone would quietly under-count exactly the customers the rule exists to protect.
 */
public final class RepossessionPolicy {

    /** Repossession is allowed as soon as anything is overdue. */
    public static final int NO_MINIMUM_OVERDUE = 0;
    /** No protected-goods threshold — the rule is off for this tenant. */
    public static final int PROTECTED_GOODS_OFF = 0;

    private RepossessionPolicy() {}

    /** Allowed, or refused with a reason meant for a person rather than a log. */
    public record Decision(boolean allowed, String reason) {
        public static Decision allow() { return new Decision(true, null); }
        public static Decision refuse(String reason) { return new Decision(false, reason); }
    }

    /**
     * @param status         the plan's status — only a live plan can be repossessed
     * @param cashPrice      what the goods cost, before any down payment
     * @param totalPaid      everything received against the plan, down payment included
     * @param worstOverdueDays how late the most overdue instalment is; 0 when nothing is late
     */
    public record PlanStanding(String status, BigDecimal cashPrice, BigDecimal totalPaid,
                               int worstOverdueDays) {}

    /**
     * @param enabled          whether this tenant repossesses at all
     * @param minOverdueDays   how late is late enough
     * @param protectedGoodsPct refuse once this much of the cash price is paid; 0 turns the rule off
     */
    public record Rules(boolean enabled, int minOverdueDays, int protectedGoodsPct) {

        /** Everything off — the shape a tenant that has not configured anything gets. */
        public static Rules off() {
            return new Rules(false, NO_MINIMUM_OVERDUE, PROTECTED_GOODS_OFF);
        }
    }

    /** Statuses a plan can be repossessed from. A completed or already-cancelled plan has nothing to take. */
    private static boolean isLive(String status) {
        return "ACTIVE".equals(status) || "DEFAULTED".equals(status);
    }

    public static Decision evaluate(PlanStanding plan, Rules rules) {
        if (plan == null || rules == null) return Decision.refuse("There is nothing to repossess.");

        if (!rules.enabled()) {
            return Decision.refuse("Repossession is switched off for this shop.");
        }
        if (!isLive(plan.status())) {
            // Named rather than generic: "this plan is already CANCELLED" tells the shopkeeper the item was
            // dealt with, which is a different problem from being refused.
            return Decision.refuse("This plan is " + plan.status() + " — only a live plan can be repossessed.");
        }

        int late = plan.worstOverdueDays();
        if (rules.minOverdueDays() > NO_MINIMUM_OVERDUE && late < rules.minOverdueDays()) {
            return Decision.refuse(late <= 0
                    ? "Nothing is overdue on this plan yet."
                    : "This plan is " + late + " day(s) late; repossession needs "
                            + rules.minOverdueDays() + ".");
        }

        int paidPct = paidPercent(plan.cashPrice(), plan.totalPaid());
        if (rules.protectedGoodsPct() > PROTECTED_GOODS_OFF && paidPct >= rules.protectedGoodsPct()) {
            return Decision.refuse("The customer has paid " + paidPct + "% of the price. Goods are protected "
                    + "at " + rules.protectedGoodsPct() + "% — this needs a court order, not a repossession.");
        }

        return Decision.allow();
    }

    /**
     * Paid share of the cash price, rounded DOWN.
     *
     * <p>Rounding down is deliberate and it is the protective direction: a customer who has paid 65.9% must
     * not be rounded up to 66 and refused, and one who has paid 66.1% must not be rounded down to 66 and — if
     * the threshold were exclusive — have their goods taken. The comparison is {@code >=}, so a customer who
     * has paid exactly the threshold is protected.
     *
     * @return 0 when the price is missing or zero, which leaves the protected-goods rule unable to fire rather
     *         than firing on a meaningless number
     */
    static int paidPercent(BigDecimal cashPrice, BigDecimal totalPaid) {
        if (cashPrice == null || cashPrice.signum() <= 0) return 0;
        BigDecimal paid = totalPaid == null ? BigDecimal.ZERO : totalPaid;
        if (paid.signum() <= 0) return 0;
        return paid.multiply(BigDecimal.valueOf(100))
                .divide(cashPrice, 0, RoundingMode.DOWN)
                .intValue();
    }
}
