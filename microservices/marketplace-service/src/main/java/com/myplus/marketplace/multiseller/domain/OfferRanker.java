package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Orders a product's eligible offers (source §18 "Routing ranking", §7 "Do not always force the cheapest offer").
 *
 * <p>Ineligible offers are removed first — a sort is a preference, never a way around the guardrails (§7.6). The
 * customer's sort becomes the PRIMARY key; the operator's default chain (delivery promise → total price →
 * promotion → rating → distance → acceptance history) breaks ties, and the offer id makes the order deterministic
 * so the same request never shows the same offers in two orders.
 *
 * <p><b>Strategy</b>: one comparator per sort; adding a sort adds a case here and touches no caller.
 */
public final class OfferRanker {

    private OfferRanker() {
    }

    /** The operator's default chain, used for ties and when the customer has not chosen. */
    static final Comparator<OfferCandidate> DEFAULT_CHAIN = Comparator
            .comparingInt(OfferCandidate::promiseHours)
            .thenComparing(OfferCandidate::effectivePrice)
            .thenComparing(OfferRanker::promotion, Comparator.reverseOrder())
            .thenComparing(OfferRanker::rating, Comparator.reverseOrder())
            .thenComparing(OfferRanker::distance)
            .thenComparing(OfferCandidate::acceptanceRate, Comparator.reverseOrder())
            .thenComparingLong(OfferCandidate::offerId);

    public static List<OfferCandidate> rank(List<OfferCandidate> offers, OfferSort sort, EligibilityContext ctx) {
        return offers.stream()
                .filter(o -> OfferEligibility.isEligible(o, ctx))
                .sorted(primary(sort).thenComparing(DEFAULT_CHAIN))
                .toList();
    }

    static Comparator<OfferCandidate> primary(OfferSort sort) {
        if (sort == null) return (a, b) -> 0;
        return switch (sort) {
            case LOWEST_PRICE -> Comparator.comparing(OfferCandidate::effectivePrice);
            case NEAREST -> Comparator.comparingDouble(OfferRanker::distance);
            case FASTEST -> Comparator.comparingInt(OfferCandidate::promiseHours);
            case PROMOTION -> Comparator.comparing(OfferRanker::promotion, Comparator.reverseOrder());
            case QUALITY -> Comparator.comparing(OfferRanker::rating, Comparator.reverseOrder());
            case WARRANTY -> Comparator.comparingInt(OfferCandidate::warrantyMonths).reversed();
            case RETURN_POLICY -> Comparator.comparingInt(OfferCandidate::returnDays).reversed();
        };
    }

    /** Unknown distance sorts LAST: an offer must not win "nearest" by not saying where it is. */
    private static double distance(OfferCandidate c) {
        return c.distanceKm() == null ? Double.MAX_VALUE : c.distanceKm();
    }

    private static BigDecimal promotion(OfferCandidate c) {
        return c.promotionDiscount() == null ? BigDecimal.ZERO : c.promotionDiscount();
    }

    private static BigDecimal rating(OfferCandidate c) {
        return c.rating() == null ? BigDecimal.ZERO : c.rating();
    }
}
