package com.myplus.business_service.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * NUM-1 / standard D14 — the ONE place quantity arithmetic is shaped.
 *
 * <p>Quantities are {@code BigDecimal} stored at {@link #SCALE} 4 ({@code DECIMAL(19,4)}). Rules this class carries so
 * no caller re-invents them:
 * <ul>
 *   <li>divide at {@link #DIV_SCALE} 6, {@code HALF_UP}, and round once when storing ({@link #stored});</li>
 *   <li>compare with {@code compareTo} — {@code 1.0.equals(1.0000)} is false and compiles ({@link #same});</li>
 *   <li>text for a person (CSV, receipt, message) through {@link #plain}: "2", never "2.0000" or "2.0".</li>
 * </ul>
 * Never build one from a float or double — {@code BigDecimal.valueOf(0.075f)} is 0.07500000298…
 */
public final class Quantities {

    /** Stored scale of every quantity column (DECIMAL(19,4)). */
    public static final int SCALE = 4;
    /** Working scale for division, before the single rounding to {@link #SCALE}. */
    public static final int DIV_SCALE = 6;

    private Quantities() { }

    /** Null as zero — the reading every "no quantity yet" site used ({@code q == null ? 0f : q}). */
    public static BigDecimal nz(BigDecimal q) {
        return q == null ? BigDecimal.ZERO : q;
    }

    /** {@code a ÷ b} at the working scale; null or zero divisor gives zero (callers guarded it the same way). */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) return BigDecimal.ZERO;
        return a.divide(b, DIV_SCALE, RoundingMode.HALF_UP);
    }

    /** The value as it will be stored: rounded once, {@code HALF_UP}, to {@link #SCALE}. */
    public static BigDecimal stored(BigDecimal q) {
        return q == null ? null : q.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Numeric equality regardless of scale (2 == 2.0000). */
    public static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** For people: no trailing zeros, no exponent — 2, 0.075, 1.5. Null as an empty string. */
    public static String plain(BigDecimal q) {
        if (q == null) return "";
        return q.signum() == 0 ? "0" : q.stripTrailingZeros().toPlainString();
    }
}
