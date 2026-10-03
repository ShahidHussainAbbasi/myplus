package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * One row of the published offer projection, as the ranker sees it (source §18 stage 1).
 *
 * <p>Holds only what the source §9.2 allows the marketplace to know: no cost, no margin. {@code distanceKm} is
 * nullable until stock has a location (INV-L); an unknown distance sorts last under NEAREST, never first.
 */
public record OfferCandidate(
        long offerId,
        long productId,
        long sellerOrganizationId,
        StockSourceType source,
        MarketplaceStatus.Approval approval,
        boolean sellerActive,
        MarketplaceStatus.Regulated regulated,
        Set<String> deliveryAreas,
        BigDecimal price,
        BigDecimal promotionDiscount,
        BigDecimal availableQty,
        int promiseHours,
        BigDecimal rating,
        int warrantyMonths,
        int returnDays,
        Double distanceKm,
        double acceptanceRate,
        Instant lastSyncAt) {

    /** What the customer pays for one unit after a seller-funded promotion. */
    public BigDecimal effectivePrice() {
        BigDecimal promo = promotionDiscount == null ? BigDecimal.ZERO : promotionDiscount;
        return price.subtract(promo).max(BigDecimal.ZERO);
    }
}
