package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;

/**
 * {@code available = on_hand − reserved − allocated − unavailable} (source §10). Never {@code stock > 0}.
 *
 * <p>Inventory already publishes {@code onHand}, {@code held} and {@code expired} (O5a); {@code unavailable} is
 * expired + quarantined. Clamped at zero: a negative figure would rank an oversold offer as if it had stock.
 */
public final class Availability {

    private Availability() {
    }

    public static BigDecimal available(BigDecimal onHand, BigDecimal reserved, BigDecimal allocated,
            BigDecimal unavailable) {
        BigDecimal v = nz(onHand).subtract(nz(reserved)).subtract(nz(allocated)).subtract(nz(unavailable));
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    public static boolean canReserve(BigDecimal requested, BigDecimal onHand, BigDecimal reserved,
            BigDecimal allocated, BigDecimal unavailable) {
        if (requested == null || requested.signum() <= 0) return false;
        return available(onHand, reserved, allocated, unavailable).compareTo(requested) >= 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
