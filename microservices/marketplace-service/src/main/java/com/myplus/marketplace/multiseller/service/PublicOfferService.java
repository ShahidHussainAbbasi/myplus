package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
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
 * MKT-1c/1d — the public catalogue as an anonymous customer sees it: search cards, a product page and its offers
 * (source §5.4, §7, §18 stage 1).
 *
 * <p>Reads ONLY the published projection (no remote call on the browse path; one indexed query). Every row goes
 * through MKT-1a's {@link OfferRanker}, which applies the guardrails first (§7.6: stale stock, out-of-area,
 * out-of-price-range, not enough stock are dropped) and then the customer's sort. An unknown sort falls back to the
 * operator's default chain instead of failing the page.
 */
@Service
@RequiredArgsConstructor
public class PublicOfferService {

    /** A search page holds at most this many cards. */
    static final int MAX_PAGE = 24;
    /** Longer search text is cut, not refused: a pasted product title must still find something. */
    static final int MAX_Q = 80;

    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceProductRepository products;
    private final MarketplaceSettingsService settings;
    /** MKT-2e: the ranking's "acceptance history" (the last tie-break before the offer id). */
    private final SellerPerformanceService performance;

    /** A product's offers, ranked. A missing or unknown sort applies the operator's default (MKT-1d). */
    @Transactional(readOnly = true)
    public List<OfferDTOs.PublicOffer> offers(Long mktProductId, String city, String sort, BigDecimal qty) {
        MarketplaceProduct product = products.findById(mktProductId).orElse(null);
        if (!visible(product)) return List.of();
        List<MarketplaceOfferProjection> rows = projections.findByMktProductIdAndStatusOrderByPriceAsc(
                mktProductId, MarketplaceOfferProjection.LIVE);
        Map<Long, MarketplaceOfferProjection> byId = rows.stream()
                .collect(Collectors.toMap(MarketplaceOfferProjection::getOfferId, Function.identity()));
        return eligible(product, rows, city, qty, settings.resolve(sort)).stream()
                .map(c -> view(byId.get(c.offerId()))).toList();
    }

    /**
     * MKT-1d search. Two queries whatever the page holds: the candidate products, then all their LIVE rows. Each
     * product's count and "from" price come from {@link #eligible}, the function the offer table uses, so the card
     * and the table cannot disagree. A product with nothing eligible for the city is left off the page (a page may
     * therefore hold fewer than {@code size} cards; {@code last} still comes from the candidate page).
     */
    @Transactional(readOnly = true)
    public PageResponse<OfferDTOs.ProductCard> search(String q, String city, Integer page, Integer size) {
        int s = size == null || size < 1 ? MAX_PAGE : Math.min(size, MAX_PAGE);
        int pg = page == null || page < 0 ? 0 : page;
        Page<MarketplaceProduct> candidates = products.publicSearch(likePattern(q), PageRequest.of(pg, s));
        Map<Long, List<MarketplaceOfferProjection>> live = candidates.isEmpty() ? Map.of()
                : projections.findByMktProductIdInAndStatus(
                        candidates.getContent().stream().map(MarketplaceProduct::getId).toList(),
                        MarketplaceOfferProjection.LIVE).stream()
                        .collect(Collectors.groupingBy(MarketplaceOfferProjection::getMktProductId));
        List<OfferDTOs.ProductCard> cards = new ArrayList<>();
        for (MarketplaceProduct p : candidates.getContent()) {
            List<OfferCandidate> ok = eligible(p, live.getOrDefault(p.getId(), List.of()), city, null, null);
            if (ok.isEmpty()) continue;
            cards.add(new OfferDTOs.ProductCard(p.getId(), p.getCanonicalName(), p.getBrand(), p.getCategoryName(),
                    ok.size(),
                    ok.stream().map(OfferCandidate::effectivePrice).min(Comparator.naturalOrder()).orElse(null),
                    ok.stream().map(OfferCandidate::promiseHours).min(Comparator.naturalOrder()).orElse(null)));
        }
        return new PageResponse<>(cards, candidates.getNumber(), candidates.getSize(), candidates.getTotalElements(),
                candidates.getTotalPages(), candidates.isLast());
    }

    /** MKT-1d product page header. Not approved or regulated reads exactly like an id that does not exist. */
    @Transactional(readOnly = true)
    public OfferDTOs.PublicProduct product(Long id) {
        MarketplaceProduct p = id == null ? null : products.findById(id).orElse(null);
        if (!visible(p)) throw new ResourceNotFoundException("No such product.");
        return new OfferDTOs.PublicProduct(p.getId(), p.getCanonicalName(), p.getBrand(), p.getModel(), p.getVariant(),
                p.getColour(), p.getSize(), p.getPackSize(), p.getConditionGrade(), p.getCategoryName(),
                settings.defaultSort(), MarketplaceSettingsService.SORTS);
    }

    /**
     * THE eligibility for the public catalogue — the one function behind both the card ("Available from N sellers")
     * and the offer table. Guardrails first (MKT-1a: stale stock, area, price limits, quantity, regulated), then the
     * sort ({@code null} = the default chain).
     */
    List<OfferCandidate> eligible(MarketplaceProduct product, List<MarketplaceOfferProjection> rows, String city,
            BigDecimal qty, OfferSort sort) {
        EligibilityContext base = EligibilityContext.phase1(city == null || city.isBlank() ? null : city.trim(),
                qty == null || qty.signum() <= 0 ? BigDecimal.ONE : qty, Instant.now());
        EligibilityContext ctx = new EligibilityContext(base.city(), base.quantity(), product.getPriceFloor(),
                product.getPriceCeiling(), base.enabledPhase(), base.blockRegulated(), base.staleAfter(), base.now(),
                settings.platformStock());                                  // MKT-3a: the warehouse's offers
        return OfferRanker.rank(rows.stream().map(p -> candidate(p, performance.acceptanceRate(p.getSellerOrganizationId()))).toList(),
                sort, ctx);
    }

    static boolean visible(MarketplaceProduct p) {
        return p != null && Approval.APPROVED.name().equals(p.getApprovalStatus())
                && Regulated.NONE.name().equals(p.getRegulatedStatus());
    }

    /** "Galaxy 50%" → "%galaxy 50!%%": lower-cased, cut to {@value #MAX_Q}, LIKE wildcards escaped with '!'. */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) return null;
        String t = q.trim();
        if (t.length() > MAX_Q) t = t.substring(0, MAX_Q);
        t = t.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + t + "%";
    }

    static OfferCandidate candidate(MarketplaceOfferProjection p, double acceptanceRate) {
        return new OfferCandidate(p.getOfferId(), p.getMktProductId(), p.getSellerOrganizationId(),
                StockSourceType.valueOf(p.getStockSourceType()), Approval.APPROVED, true,
                Regulated.valueOf(p.getRegulatedStatus()), areas(p.getDeliveryAreas()), p.getPrice(), null,
                p.getAvailableQty(), p.getPromiseHours(), null,
                p.getWarrantyMonths() == null ? 0 : p.getWarrantyMonths(),
                p.getReturnDays() == null ? 0 : p.getReturnDays(), null, acceptanceRate,
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
                p.getWarrantyExcludes(), p.getReturnDays(), List.copyOf(areas(p.getDeliveryAreas())), p.getLastSyncAt(),
                p.getLastSyncAt() == null ? null
                        : Math.max(0, java.time.Duration.between(p.getLastSyncAt(), java.time.LocalDateTime.now()).getSeconds()),
                StockSourceType.PLATFORM.name().equals(p.getStockSourceType()));                // MKT-3a
    }
}
