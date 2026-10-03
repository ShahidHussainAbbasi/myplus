package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceRuleException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.PhaseGuard;
import com.myplus.marketplace.multiseller.domain.ProductIdentityKey;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.CatalogDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1b — canonical products and the match review that feeds them (source §5, §6).
 *
 * <h3>What is trusted from where</h3>
 * <ul>
 *   <li><b>Ownership and the regulated flags</b> come from catalog-service ({@code getProductsFresh}, live, scoped by
 *       the seller's forwarded identity: a product of another tenant is simply absent). Never from the request.</li>
 *   <li><b>Identity attributes</b> are the seller's declaration; the key is computed here and the operator reviews
 *       it. The key only PROPOSES; nothing is merged without a person (MKT-R6.1).</li>
 * </ul>
 *
 * <h3>Phase 1 refuses regulated goods at the door</h3>
 * {@link PhaseGuard} with {@code blockRegulated = true} — a constant, deliberately not a setting yet: a toggle that
 * nothing could safely switch off before Phase 6's legal review would be a C1 "toggle that changes nothing", or
 * worse, one that does. It becomes a setting in MKT-6, defaulting ON and failing ON (C3).
 */
@Service
@RequiredArgsConstructor
public class MarketplaceCatalogService {

    static final int MAX_PAGE = 100;
    static final PhaseGuard PHASE = new PhaseGuard(1, true);

    private final MarketplaceProductRepository products;
    private final MarketplaceProductSourceRepository sources;
    private final MarketplaceSellerService sellers;
    private final SellerAccess access;
    private final CatalogClient catalog;

    // ── seller ─────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public CatalogDTOs.Proposal propose(CatalogDTOs.ProposeRequest req) {
        sellers.assertActiveSeller();
        if (req == null || req.sourceProductId() == null)
            throw new ValidationException("Choose the product from your catalogue.");
        Long org = access.org();

        ProductRef ref = ownProduct(req.sourceProductId());
        Regulated regulated = Boolean.TRUE.equals(ref.getRxRequired()) ? Regulated.PRESCRIPTION
                : Boolean.TRUE.equals(ref.getControlledSubstance()) ? Regulated.RESTRICTED : Regulated.NONE;
        rule(() -> PHASE.checkOffer(StockSourceType.MERCHANT, regulated));

        String key = rule(() -> ProductIdentityKey.preferGtin(req.gtin(), ProductIdentityKey.general(req.brand(),
                req.model(), req.variant(), req.colour(), req.size(), req.unit(), req.packSize(), req.condition(),
                req.warrantyType())));

        MarketplaceProductSource s = sources.findByOrganizationIdAndSourceProductId(org, req.sourceProductId())
                .orElse(null);
        if (s == null) {
            s = new MarketplaceProductSource();
            s.setOrganizationId(org);
            s.setSourceProductId(req.sourceProductId());
            s.setMatchStatus(Match.PENDING_REVIEW.name());
        } else if (Match.MATCHED.name().equals(s.getMatchStatus())) {
            throw new ValidationException("This product is already matched on the marketplace. Ask MaxTheService "
                    + "to correct the match if its details have changed.");
        } else if (!Match.PENDING_REVIEW.name().equals(s.getMatchStatus())) {
            move(s, Match.PENDING_REVIEW);   // NEEDS_CORRECTION / REJECTED → re-proposed
            s.setMktProductId(null);
        }
        s.setSourceProductName(ref.getName());
        s.setSourceRegulated(regulated.name());
        s.setProposedIdentityKey(key);
        s.setBrand(clip(req.brand(), 80));
        s.setModel(clip(req.model(), 120));
        s.setVariant(clip(req.variant(), 80));
        s.setColour(clip(req.colour(), 60));
        s.setSize(clip(req.size(), 60));
        s.setUnit(clip(req.unit(), 40));
        s.setPackSize(clip(req.packSize(), 40));
        s.setConditionGrade(clip(req.condition(), 40));
        s.setWarrantyType(clip(req.warrantyType(), 60));
        s.setGtin(ProductIdentityKey.isValidGtin(digits(req.gtin())) ? digits(req.gtin()) : null);
        // A SUGGESTION for the operator, never a merge (MKT-R6.1). Different storage/pack size → different key →
        // no suggestion at all (MKT-R6.6).
        s.setSuggestedProductId(products.findByIdentityKey(key).map(MarketplaceProduct::getId).orElse(null));
        s.setProposedByUserId(access.userId());
        s.setReviewNote(null);
        return toDto(sources.save(s));
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogDTOs.Proposal> myProposals(Integer page, Integer size) {
        return PageResponse.of(sources.findByOrganizationIdOrderByCreatedAtDesc(access.org(), page(page, size)),
                MarketplaceCatalogService::toDto);
    }

    // ── operator ───────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CatalogDTOs.Proposal> queue(String status, Integer page, Integer size) {
        access.assertOperator();
        Match m = status == null || status.isBlank() ? Match.PENDING_REVIEW : parse(status);
        // Waiting proposals are worked oldest first; a decided list is history, newest first — otherwise, once it
        // passes one page, the decision just made is the one the operator cannot see.
        return PageResponse.of(m == Match.PENDING_REVIEW
                ? sources.findByMatchStatusOrderByCreatedAtAsc(m.name(), page(page, size))
                : sources.findByMatchStatusOrderByCreatedAtDesc(m.name(), page(page, size)),
                MarketplaceCatalogService::toDto);
    }

    @Transactional
    public CatalogDTOs.Proposal decide(Long proposalId, CatalogDTOs.DecisionRequest req) {
        access.assertOperator();
        if (req == null || req.decision() == null) throw new ValidationException("Choose a decision.");
        Match target = parse(req.decision());
        MarketplaceProductSource s = sources.findById(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("No such product proposal."));
        if (req.version() != null && !req.version().equals(s.getVersion()))
            throw new OptimisticLockingFailureException("proposal changed");
        String note = trim(req.note());
        switch (target) {
            case MATCHED -> {
                move(s, Match.MATCHED);
                s.setMktProductId(canonicalFor(s, req.mktProductId()).getId());
                s.setReviewNote(note);
            }
            case NEEDS_CORRECTION, REJECTED -> {
                if (note == null)
                    throw new ValidationException("Tell the seller what is wrong. They will see your note as written.");
                move(s, target);
                s.setMktProductId(null);
                s.setReviewNote(note);
            }
            default -> throw new ValidationException("Choose Match, Needs correction or Reject.");
        }
        s.setReviewedByUserId(access.userId());
        s.setReviewedAt(LocalDateTime.now());
        return toDto(sources.save(s));
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogDTOs.Product> canonicalProducts(String q, Integer page, Integer size) {
        access.assertOperator();
        var p = page(page, size);
        return PageResponse.of(q == null || q.isBlank()
                ? products.findAllByOrderByCanonicalNameAsc(p)
                : products.findByCanonicalNameContainingIgnoreCaseOrderByCanonicalNameAsc(q.trim(), p),
                MarketplaceCatalogService::toDto);
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The canonical product a MATCHED proposal points at: the operator's explicit choice, else the existing product
     * with the same key, else a new one built from the proposal. A regulated proposal can never be attached to a
     * product recorded as NONE (or the reverse) — that would launder a prescription medicine into an ordinary row.
     */
    private MarketplaceProduct canonicalFor(MarketplaceProductSource s, Long chosen) {
        Long id = chosen != null ? chosen : s.getSuggestedProductId();
        if (id != null) {
            MarketplaceProduct p = products.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("No such marketplace product."));
            if (!p.getRegulatedStatus().equals(s.getSourceRegulated()))
                throw new ValidationException("This product is " + s.getSourceRegulated().toLowerCase()
                        + " in the seller's catalogue but " + p.getRegulatedStatus().toLowerCase()
                        + " on the marketplace. They cannot be the same product.");
            return p;
        }
        MarketplaceProduct p = new MarketplaceProduct();
        p.setIdentityKey(s.getProposedIdentityKey());
        p.setBrand(s.getBrand());
        p.setModel(s.getModel());
        p.setVariant(s.getVariant());
        p.setColour(s.getColour());
        p.setSize(s.getSize());
        p.setUnit(s.getUnit());
        p.setPackSize(s.getPackSize());
        p.setConditionGrade(s.getConditionGrade());
        p.setWarrantyType(s.getWarrantyType());
        p.setGtin(s.getGtin());
        p.setCanonicalName(clip(Stream.of(s.getBrand(), s.getModel(), s.getVariant(), s.getColour(), s.getSize(),
                s.getPackSize()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" ")), 255));
        p.setRegulatedStatus(s.getSourceRegulated());
        p.setApprovalStatus(Approval.APPROVED.name());
        p.setCreatedByUserId(access.userId());
        try {
            return products.saveAndFlush(p);
        } catch (DataIntegrityViolationException race) {
            // the same key was created a moment ago by another decision: that row IS this product
            return products.findByIdentityKey(s.getProposedIdentityKey()).orElseThrow(() -> race);
        }
    }

    private ProductRef ownProduct(Long id) {
        List<ProductRef> refs = catalog.getProductsFresh(List.of(id), true);
        // catalog scopes by the forwarded identity: another tenant's product is ABSENT, never an error — so absent
        // and foreign read the same here, and the seller learns nothing about ids outside their catalogue
        return (refs == null ? List.<ProductRef>of() : refs).stream().filter(r -> id.equals(r.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("That product is not in your catalogue."));
    }

    private static void move(MarketplaceProductSource s, Match to) {
        try {
            MarketplaceStateMachines.MATCH.transition(Match.valueOf(s.getMatchStatus()), to);
        } catch (MarketplaceRuleException e) {
            throw new ValidationException("A proposal that is " + words(s.getMatchStatus()) + " cannot be moved to "
                    + words(to.name()) + ".");
        }
        s.setMatchStatus(to.name());
    }

    /** Domain refusals become 400s with the domain's own sentence (the shared handler knows ValidationException). */
    private static <T> T rule(java.util.function.Supplier<T> f) {
        try {
            return f.get();
        } catch (MarketplaceRuleException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    private static void rule(Runnable r) {
        rule(() -> {
            r.run();
            return null;
        });
    }

    private static Match parse(String s) {
        try {
            return Match.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown match status: " + s);
        }
    }

    private static PageRequest page(Integer page, Integer size) {
        return PageRequest.of(page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? 50 : Math.min(size, MAX_PAGE));
    }

    private static String words(String status) {
        return status.toLowerCase().replace('_', ' ');
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        String t = trim(s);
        return t == null ? null : (t.length() > max ? t.substring(0, max) : t);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\s", "");
    }

    static CatalogDTOs.Proposal toDto(MarketplaceProductSource s) {
        return new CatalogDTOs.Proposal(s.getId(), s.getOrganizationId(), s.getSourceProductId(),
                s.getSourceProductName(), s.getProposedIdentityKey(), s.getBrand(), s.getModel(), s.getVariant(),
                s.getColour(), s.getSize(), s.getUnit(), s.getPackSize(), s.getConditionGrade(), s.getWarrantyType(),
                s.getGtin(), s.getSourceRegulated(), s.getMatchStatus(), s.getSuggestedProductId(),
                s.getMktProductId(), s.getReviewNote(), s.getCreatedAt(), s.getReviewedAt(), s.getVersion());
    }

    static CatalogDTOs.Product toDto(MarketplaceProduct p) {
        return new CatalogDTOs.Product(p.getId(), p.getIdentityKey(), p.getCanonicalName(), p.getBrand(),
                p.getModel(), p.getVariant(), p.getColour(), p.getSize(), p.getUnit(), p.getPackSize(),
                p.getConditionGrade(), p.getWarrantyType(), p.getGtin(), p.getRegulatedStatus(),
                p.getApprovalStatus());
    }
}
