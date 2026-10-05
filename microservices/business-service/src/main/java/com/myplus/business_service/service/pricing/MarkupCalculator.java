package com.myplus.business_service.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PR-2 — the selling price a purchase cost suggests, under the business's markup rule. Pure: no settings, no catalog,
 * no clock — every input is an argument, so the purchase form's suggestion and Auto's price come from the same
 * arithmetic and cannot disagree.
 *
 * <h3>Markup and margin are different numbers</h3>
 * Shops say "14.5%" and mean one of two things. MARKUP is on the cost: 100 × 1.145 = <b>114.50</b> (the margin on that
 * price is 12.66%). MARGIN is of the selling price: 100 ÷ 0.855 = <b>116.96</b>. The owner picks which they mean; the
 * settings screen says both numbers so the choice is not a guess.
 *
 * <h3>Rounding</h3>
 * {@code exact} — to the paisa, half up. {@code up1} — UP to the next whole rupee (never below the rule's price).
 * {@code near5} / {@code near10} — to the nearest 5 / 10, half up; a shelf price, which may sit a little under the rule.
 */
public final class MarkupCalculator {

    public static final String MARKUP = "markup";
    public static final String MARGIN = "margin";
    public static final String EXACT = "exact";
    public static final String UP1 = "up1";
    public static final String NEAR5 = "near5";
    public static final String NEAR10 = "near10";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private MarkupCalculator() {}

    /** The rule's price before rounding, to the paisa. Null when there is nothing to compute (no cost, no %). */
    public static BigDecimal raw(BigDecimal cost, BigDecimal pct, String basis) {
        if (cost == null || cost.signum() <= 0 || pct == null || pct.signum() <= 0) return null;
        BigDecimal p = pct.divide(HUNDRED, 10, RoundingMode.HALF_UP);
        if (MARGIN.equals(basis)) {
            // A margin of 100% or more has no price: cost ÷ 0 or a negative. Refused, never turned into a number.
            if (p.compareTo(BigDecimal.ONE) >= 0) throw new IllegalArgumentException("A margin must be under 100%");
            return cost.divide(BigDecimal.ONE.subtract(p), 2, RoundingMode.HALF_UP);
        }
        return cost.multiply(BigDecimal.ONE.add(p)).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code value} rounded the shop's way; an unknown rounding is {@code exact}. */
    public static BigDecimal round(BigDecimal value, String rounding) {
        if (value == null) return null;
        if (UP1.equals(rounding)) return value.setScale(0, RoundingMode.CEILING).setScale(2, RoundingMode.UNNECESSARY);
        if (NEAR5.equals(rounding)) return nearest(value, new BigDecimal("5"));
        if (NEAR10.equals(rounding)) return nearest(value, BigDecimal.TEN);
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** raw then round. */
    public static BigDecimal price(BigDecimal cost, BigDecimal pct, String basis, String rounding) {
        return round(raw(cost, pct, basis), rounding);
    }

    private static BigDecimal nearest(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.UNNECESSARY);
    }
}
