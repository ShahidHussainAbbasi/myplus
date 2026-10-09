package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * The facts an offer is judged against: who is asking, for what, and the operator's limits (source §7.4, §7.6).
 *
 * @param city             the customer's delivery city; null means "browsing, no city chosen yet"
 * @param quantity         the quantity wanted (1 when browsing)
 * @param priceFloor       the operator's minimum price for this product, or null
 * @param priceCeiling     the operator's maximum price for this product, or null
 * @param enabledPhase     the highest MKT phase live for this operator (1 at launch)
 * @param blockRegulated   {@code mkt.phase1.blockRegulated} — a safety flag: defaults ON and fails ON (C3)
 * @param staleAfter       an offer whose projection is older than this is not shown (K-5)
 * @param now              the clock, injected so tests are deterministic
 * @param platformStock    MKT-3a: the operator has named the MaxTheService warehouse, so PLATFORM offers are shown
 */
public record EligibilityContext(String city, BigDecimal quantity, BigDecimal priceFloor, BigDecimal priceCeiling,
        int enabledPhase, boolean blockRegulated, Duration staleAfter, Instant now, boolean platformStock) {

    public EligibilityContext(String city, BigDecimal quantity, BigDecimal priceFloor, BigDecimal priceCeiling,
            int enabledPhase, boolean blockRegulated, Duration staleAfter, Instant now) {
        this(city, quantity, priceFloor, priceCeiling, enabledPhase, blockRegulated, staleAfter, now, false);
    }

    public static EligibilityContext phase1(String city, BigDecimal quantity, Instant now) {
        return new EligibilityContext(city, quantity, null, null, 1, true, Duration.ofMinutes(30), now);
    }
}
