package com.myplus.marketplace.multiseller.domain;

import java.util.Collection;
import java.util.Objects;

/**
 * Phase 1 scope, enforced on the server (source §17, §20 "Do not include").
 *
 * <p>The source is explicit that a multi-seller cart is not "merely a frontend filter": until Phase 2 builds
 * order splitting, payment allocation and per-seller returns, a checkout naming two sellers must be refused here,
 * whatever the page allowed. Likewise dropship, consignment and prescription medicines.
 */
public final class PhaseGuard {

    /** One line of a checkout request, as far as the guard cares. */
    public record CheckoutLine(long offerId, long sellerOrganizationId, StockSourceType source,
            MarketplaceStatus.Regulated regulated) {
    }

    private final int enabledPhase;
    private final boolean blockRegulated;

    public PhaseGuard(int enabledPhase, boolean blockRegulated) {
        this.enabledPhase = enabledPhase;
        this.blockRegulated = blockRegulated;
    }

    public void checkCheckout(Collection<CheckoutLine> lines) {
        if (lines == null || lines.isEmpty())
            throw new MarketplaceRuleException("EMPTY_CHECKOUT", "Your basket is empty.");
        long sellers = lines.stream().map(CheckoutLine::sellerOrganizationId).distinct().count();
        if (enabledPhase < 2 && sellers > 1)
            throw new MarketplaceRuleException("ONE_SELLER_PER_CHECKOUT",
                    "Items from different sellers must be checked out separately.");
        for (CheckoutLine l : lines) checkOffer(l.source(), l.regulated());
    }

    public void checkOffer(StockSourceType source, MarketplaceStatus.Regulated regulated) {
        Objects.requireNonNull(source, "source");
        if (source.launchPhase() > enabledPhase)
            throw new MarketplaceRuleException("SOURCE_NOT_ENABLED",
                    "Offers from " + source.name().toLowerCase() + " stock are not available yet.");
        if (blockRegulated && regulated != null && regulated != MarketplaceStatus.Regulated.NONE)
            throw new MarketplaceRuleException("REGULATED_BLOCKED",
                    "Prescription and restricted products cannot be sold on the marketplace yet.");
    }
}
