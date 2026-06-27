package com.myplus.commerce.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Null-safe money arithmetic shared across commerce domains (slice 33, Phase 2). Money entity/DTO fields
 * are {@code BigDecimal(19,2)} (slice 23); these helpers treat {@code null} as zero and apply the standard
 * 2-dp HALF_UP rounding so totals/dues are computed consistently everywhere.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() {}

    /** {@code null} -> 0.00, otherwise the value scaled to 2dp. */
    public static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return nz(a).add(nz(b)).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return nz(a).subtract(nz(b)).setScale(SCALE, ROUNDING);
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return nz(a).multiply(nz(b)).setScale(SCALE, ROUNDING);
    }

    /** Scale an arbitrary value to money precision (2dp HALF_UP), null-safe. */
    public static BigDecimal scale(BigDecimal v) {
        return nz(v);
    }
}
