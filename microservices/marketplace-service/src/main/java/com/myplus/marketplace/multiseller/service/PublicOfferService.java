package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.marketplace.multiseller.domain.EligibilityContext;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.OfferCandidate;
import com.myplus.marketplace.multiseller.domain.OfferRanker;
import com.myplus.marketplace.multiseller.domain.OfferSort;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1c — a product's offers as an anonymous customer sees them (source §7, §18 stage 1).
 *
 * <p>Reads ONLY the published projection (no remote call on the browse path; one indexed query). Every row goes
 * through MKT-1a's {@link OfferRanker}, which applies the guardrails first (§7.6: stale stock, out-of-area,
 * out-of-price-range, not enough stock are dropped) and then the customer's sort. An unknown sort falls back to the
 * operator's default chain instead of failing the page.
 */
@Service
@RequiredArgsConstructor
public class PublicOfferService {

    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceProductRepository products;

    @Transactional(readOnly = true)
    public List<OfferDTOs.PublicOffer> offers(Long mktProductId, String city, String sort, BigDecimal qty) {
        MarketplaceProduct product = products.findById(mktProductId).orElse(null);
        if (product == null || !Approval.APPROVED.name().equals(product.getApprovalStatus())) return List.of();
        List<MarketplaceOfferProjection> rows = projections.findByMktProductIdAndStatusOrderByPriceAsc(
                mktProductId, MarketplaceOfferProjection.LIVE);
        Map<Long, MarketplaceOfferProjection> byId = rows.stream()
                .collect(Collectors.toMap(MarketplaceOfferProjection::getOfferId, Function.identity()));
        EligibilityContext base = EligibilityContext.phase1(city == null || city.isBlank() ? null : city.trim(),
                qty == null || qty.signum() <= 0 ? BigDecimal.ONE : qty, Instant.now());
        EligibilityContext ctx = new EligibilityContext(base.city(), base.quantity(), product.getPriceFloor(),
                product.getPriceCeiling(), base.enabledPhase(), base.blockRegulated(), base.staleAfter(), base.now());
        return OfferRanker.rank(rows.stream().map(PublicOfferService::candidate).toList(), OfferSort.parse(sort), ctx)
                .stream().map(c -> view(byId.get(c.offerId()))).toList();
    }

    static OfferCandidate candidate(MarketplaceOfferProjection p) {
        return new OfferCandidate(p.getOfferId(), p.getMktProductId(), p.getSellerOrganizationId(),
                StockSourceType.valueOf(p.getStockSourceType()), Approval.APPROVED, true,
                Regulated.valueOf(p.getRegulatedStatus()), areas(p.getDeliveryAreas()), p.getPrice(), null,
                p.getAvailableQty(), p.getPromiseHours(), null,
                p.getWarrantyMonths() == null ? 0 : p.getWarrantyMonths(),
                p.getReturnDays() == null ? 0 : p.getReturnDays(), null, 0d,
                p.getLastSyncAt() == null ? null : p.getLastSyncAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    static Set<String> areas(String s) {
        return s == null ? Set.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static OfferDTOs.PublicOffer view(MarketplaceOfferProjection p) {
        return new OfferDTOs.PublicOffer(p.getOfferId(), p.getMktProductId(), p.getSellerOrganizationId(),
                p.getSellerDisplayName(), p.getPrice(), p.getAvailableQty(), p.getPromiseHours(), null,
                p.getWarrantyMonths(), p.getWarrantyProvider(), p.getWarrantyStarts(), p.getWarrantyCovers(),
                p.getWarrantyExcludes(), p.getReturnDays(), List.copyOf(areas(p.getDeliveryAreas())), p.getLastSyncAt());
    }
}
