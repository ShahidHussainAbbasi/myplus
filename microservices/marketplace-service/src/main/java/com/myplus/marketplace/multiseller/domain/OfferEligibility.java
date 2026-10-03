package com.myplus.marketplace.multiseller.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The guardrails no customer filter may bypass (source §7.6): approval, seller eligibility, regulated-product
 * restrictions, the phase's stock sources, delivery area, stock and price limits — plus projection freshness.
 *
 * <p>A <b>Specification</b>: one place, evaluated identically when browsing (to hide an offer) and at checkout (to
 * refuse it). Returning the refusal sentence rather than a boolean means the checkout tells the customer WHY,
 * in words (standard 8d), and the two paths cannot disagree about the reason.
 */
public final class OfferEligibility {

    private OfferEligibility() {
    }

    public static boolean isEligible(OfferCandidate c, EligibilityContext ctx) {
        return refusal(c, ctx).isEmpty();
    }

    public static Optional<String> refusal(OfferCandidate c, EligibilityContext ctx) {
        if (c.approval() != MarketplaceStatus.Approval.APPROVED)
            return Optional.of("This offer is not available. Please choose another offer.");
        if (!c.sellerActive())
            return Optional.of("This seller is not taking orders right now. Please choose another offer.");
        if (c.source().launchPhase() > ctx.enabledPhase())
            return Optional.of("This offer is not available yet. Please choose another offer.");
        if (ctx.blockRegulated() && c.regulated() != MarketplaceStatus.Regulated.NONE)
            return Optional.of("This product cannot be ordered on the marketplace.");
        if (ctx.city() != null && !servesCity(c, ctx.city()))
            return Optional.of("This seller does not deliver to " + ctx.city() + ".");
        if (c.lastSyncAt() == null || c.lastSyncAt().isBefore(ctx.now().minus(ctx.staleAfter())))
            return Optional.of("We are checking availability for this offer. Please choose another offer.");
        if (c.availableQty() == null || ctx.quantity() == null || c.availableQty().compareTo(ctx.quantity()) < 0)
            return Optional.of("This seller does not have enough stock. Please choose another offer.");
        if (ctx.priceFloor() != null && c.effectivePrice().compareTo(ctx.priceFloor()) < 0)
            return Optional.of("This offer's price is outside the allowed range.");
        if (ctx.priceCeiling() != null && c.effectivePrice().compareTo(ctx.priceCeiling()) > 0)
            return Optional.of("This offer's price is outside the allowed range.");
        return Optional.empty();
    }

    private static boolean servesCity(OfferCandidate c, String city) {
        if (c.deliveryAreas() == null) return false;
        String want = city.trim().toLowerCase(Locale.ROOT);
        return c.deliveryAreas().stream().anyMatch(a -> a != null && a.trim().toLowerCase(Locale.ROOT).equals(want));
    }
}
