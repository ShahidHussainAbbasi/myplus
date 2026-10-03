package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceRuleException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.PhaseGuard;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1c — offers: a seller's terms for a canonical product, and the operator's approval (source §3, §4.1, §5, §7).
 *
 * <h3>Server-authoritative (source §22)</h3>
 * <ul>
 *   <li>The four party ids come from the TOKEN and the stock source — never the request.</li>
 *   <li>The seller must have a MATCHED source for the product; that source's catalogue product is what stock is
 *       read for. A seller cannot offer a product it does not stock.</li>
 *   <li>Price must sit inside the operator's floor/ceiling for the product (source §7.4).</li>
 *   <li>Phase 1: MERCHANT stock only, nothing regulated ({@link PhaseGuard}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MarketplaceOfferService {

    static final int MAX_PAGE = 100;
    static final PhaseGuard PHASE = MarketplaceCatalogService.PHASE;

    private final MarketplaceOfferRepository offers;
    private final MarketplaceProductRepository products;
    private final MarketplaceProductSourceRepository sources;
    private final MarketplaceSellerService sellers;
    private final MarketplacePolicyService policies;
    private final OfferProjectionService projection;
    private final SellerAccess access;

    // ── seller ─────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public OfferDTOs.Offer save(OfferDTOs.SaveRequest req) {
        sellers.assertActiveSeller();
        if (req == null) throw new ValidationException("Nothing to save.");
        Long org = access.org();
        MarketplaceOffer o;
        if (req.id() == null) {
            if (req.mktProductId() == null) throw new ValidationException("Choose the marketplace product.");
            if (offers.findByOrganizationIdAndMktProductId(org, req.mktProductId()).isPresent())
                throw new ValidationException("You already have an offer for this product. Edit that one.");
            o = new MarketplaceOffer();
            o.setOrganizationId(org);
            o.setMktProductId(req.mktProductId());
            o.setApprovalStatus(Approval.DRAFT.name());
            o.setPaused(false);
        } else {
            o = offers.findByIdAndOrganizationId(req.id(), org)
                    .orElseThrow(() -> new ResourceNotFoundException("No such offer."));
            if (req.version() != null && !req.version().equals(o.getVersion()))
                throw new OptimisticLockingFailureException("offer changed");
        }

        MarketplaceProduct product = products.findById(o.getMktProductId())
                .orElseThrow(() -> new ResourceNotFoundException("No such marketplace product."));
        MarketplaceProductSource source = matchedSource(org, product.getId());

        StockSourceType stockSource = parseSource(req.stockSourceType() != null ? req.stockSourceType()
                : (o.getStockSourceType() == null ? StockSourceType.MERCHANT.name() : o.getStockSourceType()));
        rule(() -> PHASE.checkOffer(stockSource, Regulated.valueOf(product.getRegulatedStatus())));

        // ── terms (a partial edit keeps what it does not mention) ──
        if (req.marketplacePrice() != null) o.setMarketplacePrice(req.marketplacePrice().setScale(2, java.math.RoundingMode.HALF_UP));
        if (o.getMarketplacePrice() == null || o.getMarketplacePrice().signum() <= 0)
            throw new ValidationException("Set a marketplace price above zero.");
        priceWithinLimits(o.getMarketplacePrice(), product);
        if (req.listPrice() != null) o.setListPrice(req.listPrice().setScale(2, java.math.RoundingMode.HALF_UP));
        if (req.sellerSku() != null) o.setSellerSku(req.sellerSku().isBlank() ? null : req.sellerSku().trim());
        if (req.deliveryAreas() != null) o.setDeliveryAreas(areas(req.deliveryAreas()));
        if (o.getDeliveryAreas() == null) throw new ValidationException("Name at least one city you deliver to.");
        if (req.promiseHours() != null) {
            if (req.promiseHours() < 1 || req.promiseHours() > 720)
                throw new ValidationException("The delivery promise must be between 1 and 720 hours.");
            o.setPromiseHours(req.promiseHours());
        }
        if (o.getPromiseHours() == null) o.setPromiseHours(24);
        if (req.warrantyPolicyId() != null) o.setWarrantyPolicyId(policies.usable(req.warrantyPolicyId(), MarketplacePolicy.WARRANTY).getId());
        if (req.returnPolicyId() != null) o.setReturnPolicyId(policies.usable(req.returnPolicyId(), MarketplacePolicy.RETURN).getId());
        if (req.paused() != null) {
            if (req.paused() && !Approval.APPROVED.name().equals(o.getApprovalStatus()))
                throw new ValidationException("Only a live offer can be paused.");
            o.setPaused(req.paused());
        }

        // ── parties: stamped, never read (Phase 1: MERCHANT — the seller is all four) ──
        o.setSourceProductId(source.getSourceProductId());
        o.setStockSourceType(stockSource.name());
        o.setSellerOrganizationId(org);
        o.setStockOwnerOrganizationId(org);
        o.setCustodianOrganizationId(org);
        o.setFulfillerOrganizationId(org);

        o = offers.save(o);
        projection.publish(o);
        return toDto(o, product);
    }

    @Transactional
    public OfferDTOs.Offer submit(Long id) {
        sellers.assertActiveSeller();
        MarketplaceOffer o = offers.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("No such offer."));
        if (o.getWarrantyPolicyId() == null || o.getReturnPolicyId() == null)
            throw new ValidationException("Choose a warranty and a return policy before sending the offer for approval.");
        move(o, Approval.PENDING_REVIEW);
        o.setReviewNote(null);
        o = offers.save(o);
        projection.publish(o);
        return toDto(o, products.findById(o.getMktProductId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferDTOs.Offer> mine(Integer page, Integer size) {
        return PageResponse.of(offers.findByOrganizationIdOrderByCreatedAtDesc(access.org(), page(page, size)),
                o -> toDto(o, products.findById(o.getMktProductId()).orElse(null)));
    }

    @Transactional(readOnly = true)
    public OfferDTOs.Offer one(Long id) {
        MarketplaceOffer o = offers.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("No such offer."));
        return toDto(o, products.findById(o.getMktProductId()).orElse(null));
    }

    // ── operator ───────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<OfferDTOs.Offer> queue(String status, Integer page, Integer size) {
        access.assertOperator();
        String s = status == null || status.isBlank() ? Approval.PENDING_REVIEW.name() : parseApproval(status).name();
        // Waiting offers are worked oldest first; any decided list is history, newest first — otherwise, once it passes a
        // page, the offer just decided is the one the operator cannot see (found by the 1c regression at 122 approved).
        return PageResponse.of(Approval.PENDING_REVIEW.name().equals(s)
                ? offers.findByApprovalStatusOrderByCreatedAtAsc(s, page(page, size))
                : offers.findByApprovalStatusOrderByCreatedAtDesc(s, page(page, size)),
                o -> toDto(o, products.findById(o.getMktProductId()).orElse(null)));
    }

    @Transactional
    public OfferDTOs.Offer decide(Long id, OfferDTOs.DecisionRequest req) {
        access.assertOperator();
        if (req == null || req.decision() == null) throw new ValidationException("Choose a decision.");
        MarketplaceOffer o = offers.findById(id).orElseThrow(() -> new ResourceNotFoundException("No such offer."));
        if (req.version() != null && !req.version().equals(o.getVersion()))
            throw new OptimisticLockingFailureException("offer changed");
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        MarketplaceProduct product = products.findById(o.getMktProductId()).orElse(null);
        switch (req.decision().trim().toUpperCase()) {
            case "APPROVE", "REINSTATE" -> {
                if (product != null) priceWithinLimits(o.getMarketplacePrice(), product);
                move(o, Approval.APPROVED);
                // commission is the OPERATOR's term, stamped at approval and never chosen by the seller
                o.setCommissionPolicyId(policies.defaultCommission().getId());
                if (o.getPublishedAt() == null) o.setPublishedAt(LocalDateTime.now());
            }
            case "REJECT", "SUSPEND" -> {
                if (note == null) throw new ValidationException("Give the seller a reason. They will see it as written.");
                move(o, "REJECT".equalsIgnoreCase(req.decision().trim()) ? Approval.REJECTED : Approval.SUSPENDED);
            }
            default -> throw new ValidationException("Choose Approve, Reject, Suspend or Reinstate.");
        }
        o.setReviewNote(note);
        o.setReviewedByUserId(access.userId());
        o.setReviewedAt(LocalDateTime.now());
        o = offers.save(o);
        projection.publish(o);
        return toDto(o, product);
    }

    /** Operator price limits for a canonical product. Existing offers outside the new range are re-published. */
    @Transactional
    public void setLimits(Long productId, OfferDTOs.LimitsRequest req) {
        access.assertOperator();
        MarketplaceProduct p = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No such marketplace product."));
        BigDecimal floor = req == null ? null : req.priceFloor(), ceiling = req == null ? null : req.priceCeiling();
        if (floor != null && ceiling != null && floor.compareTo(ceiling) > 0)
            throw new ValidationException("The price floor cannot be above the ceiling.");
        p.setPriceFloor(floor);
        p.setPriceCeiling(ceiling);
        products.save(p);
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────

    private MarketplaceProductSource matchedSource(Long org, Long mktProductId) {
        return sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(org, mktProductId, Match.MATCHED.name())
                .orElseThrow(() -> new ValidationException("Propose this product from your catalogue and wait for "
                        + "MaxTheService to match it before making an offer."));
    }

    static void priceWithinLimits(BigDecimal price, MarketplaceProduct p) {
        if ((p.getPriceFloor() != null && price.compareTo(p.getPriceFloor()) < 0)
                || (p.getPriceCeiling() != null && price.compareTo(p.getPriceCeiling()) > 0))
            throw new ValidationException("This offer's price is outside the allowed range"
                    + (p.getPriceFloor() != null || p.getPriceCeiling() != null
                            ? " (" + (p.getPriceFloor() == null ? "no minimum" : "minimum Rs. " + p.getPriceFloor().toPlainString())
                                    + ", " + (p.getPriceCeiling() == null ? "no maximum" : "maximum Rs. " + p.getPriceCeiling().toPlainString()) + ")."
                            : "."));
    }

    /** "Karachi, karachi ,Lahore" → "Karachi,Lahore": trimmed, de-duplicated case-insensitively, order kept. */
    static String areas(String raw) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> lower = new java.util.HashSet<>();
        for (String a : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (a.length() > 60) throw new ValidationException("A city name is at most 60 characters.");
            if (lower.add(a.toLowerCase())) seen.add(a);
        }
        String joined = seen.stream().collect(Collectors.joining(","));
        if (joined.length() > 500) throw new ValidationException("Too many delivery areas for one offer.");
        return joined.isEmpty() ? null : joined;
    }

    private static void move(MarketplaceOffer o, Approval to) {
        try {
            MarketplaceStateMachines.OFFER.transition(Approval.valueOf(o.getApprovalStatus()), to);
        } catch (MarketplaceRuleException e) {
            throw new ValidationException("An offer that is " + o.getApprovalStatus().toLowerCase().replace('_', ' ')
                    + " cannot be moved to " + to.name().toLowerCase().replace('_', ' ') + ".");
        }
        o.setApprovalStatus(to.name());
    }

    private static StockSourceType parseSource(String s) {
        try {
            return StockSourceType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown stock source: " + s);
        }
    }

    private static Approval parseApproval(String s) {
        try {
            return Approval.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown offer status: " + s);
        }
    }

    private static void rule(Runnable r) {
        try {
            r.run();
        } catch (MarketplaceRuleException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    private static PageRequest page(Integer page, Integer size) {
        return PageRequest.of(page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? 50 : Math.min(size, MAX_PAGE));
    }

    static OfferDTOs.Offer toDto(MarketplaceOffer o, MarketplaceProduct p) {
        return new OfferDTOs.Offer(o.getId(), o.getOrganizationId(), o.getMktProductId(),
                p == null ? null : p.getCanonicalName(), o.getSourceProductId(), o.getStockSourceType(),
                o.getStockOwnerOrganizationId(), o.getCustodianOrganizationId(), o.getSellerOrganizationId(),
                o.getFulfillerOrganizationId(), o.getSellerSku(), o.getListPrice(), o.getMarketplacePrice(),
                o.getDeliveryAreas(), o.getPromiseHours(), o.getWarrantyPolicyId(), o.getReturnPolicyId(),
                o.getCommissionPolicyId(), o.getApprovalStatus(), o.getPaused(), o.getReviewNote(),
                o.getPublishedAt(), o.getCreatedAt(), o.getVersion());
    }
}
