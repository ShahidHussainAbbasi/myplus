package com.myplus.marketplace.multiseller.domain;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * How long the responsible stakeholder has to accept, and how long stock is held (source §10).
 *
 * <p>Named for what it governs so it is not confused with inventory-service's {@code ReservationPolicy}, which is
 * the per-tenant hold TTL this feeds. The defaults are the source's starting points, overridden per tenant through
 * {@code common-settings} ({@code mkt.accept.*}, {@code mkt.hold.*}); they are not permanent values.
 */
public final class AcceptanceTerms {

    /** Hold must outlive the acceptance deadline, or an accepted order could find its stock already released. */
    public record Terms(Duration acceptWithin, Duration holdFor) {
        public Terms {
            if (acceptWithin == null || holdFor == null || acceptWithin.isNegative())
                throw new IllegalArgumentException("acceptance terms must be non-negative");
            if (holdFor.compareTo(acceptWithin) < 0)
                throw new IllegalArgumentException("the hold must last at least as long as the acceptance window");
        }
    }

    private final Map<StockSourceType, Terms> terms = new EnumMap<>(StockSourceType.class);

    public static AcceptanceTerms defaults() {
        return new AcceptanceTerms()
                .set(StockSourceType.MERCHANT, new Terms(Duration.ofMinutes(5), Duration.ofMinutes(10)))
                // internal, immediate acceptance; held until pick/pack or cancellation (a day bounds a lost task)
                .set(StockSourceType.PLATFORM, new Terms(Duration.ZERO, Duration.ofHours(24)))
                .set(StockSourceType.SUPPLIER, new Terms(Duration.ofMinutes(20), Duration.ofMinutes(30)))
                .set(StockSourceType.CONSIGNMENT, new Terms(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    }

    public AcceptanceTerms set(StockSourceType source, Terms t) {
        terms.put(source, t);
        return this;
    }

    public Terms forSource(StockSourceType source) {
        Terms t = terms.get(source);
        if (t == null)
            throw new MarketplaceRuleException("NO_ACCEPTANCE_TERMS", "No acceptance terms are set for " + source + ".");
        return t;
    }
}
