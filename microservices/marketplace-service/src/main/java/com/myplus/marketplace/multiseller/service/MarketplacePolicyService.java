package com.myplus.marketplace.multiseller.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.CommissionPolicy;
import com.myplus.marketplace.multiseller.domain.MarketplaceRuleException;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1c — warranty, return and commission policies (source §7.4, §13, §14). The operator writes them; sellers pick
 * among the ACTIVE warranty and return policies; commission is stamped by the operator's default at approval.
 *
 * <p>Append-only: there is create and deactivate, and no edit. Changing terms means a new policy, so nothing already
 * sold under the old one can be rewritten (source §13 "future policy changes must not alter an old order").
 */
@Service
@RequiredArgsConstructor
public class MarketplacePolicyService {

    private final MarketplacePolicyRepository policies;
    private final SellerAccess access;
    /** G-16 (R22.4): every marketplace action audited, filed under the seller it concerns. */
    private final MarketplaceAuditService audit;

    @Transactional
    public OfferDTOs.Policy create(OfferDTOs.PolicyRequest req) {
        access.assertOperator();
        if (req == null || req.policyType() == null) throw new ValidationException("Choose the kind of policy.");
        String type = req.policyType().trim().toUpperCase();
        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isEmpty() || name.length() > 120)
            throw new ValidationException("Name the policy (up to 120 characters).");
        MarketplacePolicy p = new MarketplacePolicy();
        p.setPolicyType(type);
        p.setName(name);
        p.setCreatedByUserId(access.userId());
        switch (type) {
            case MarketplacePolicy.WARRANTY -> {
                if (req.warrantyProvider() == null || req.warrantyProvider().isBlank())
                    throw new ValidationException("Name the warranty provider. MaxTheService is never assumed to be it.");
                if (req.warrantyMonths() == null || req.warrantyMonths() < 0 || req.warrantyMonths() > 120)
                    throw new ValidationException("Warranty months must be between 0 and 120.");
                p.setWarrantyProvider(req.warrantyProvider().trim());
                p.setWarrantyMonths(req.warrantyMonths());
                p.setWarrantyStarts("DELIVERY");
                p.setWarrantyCovers(clip(req.warrantyCovers()));
                p.setWarrantyExcludes(clip(req.warrantyExcludes()));
                p.setClaimProcess(clip(req.claimProcess() == null ? "Open a MaxTheService support case" : req.claimProcess()));
            }
            case MarketplacePolicy.RETURN -> {
                if (req.returnDays() == null || req.returnDays() < 0 || req.returnDays() > 365)
                    throw new ValidationException("Return days must be between 0 and 365.");
                p.setReturnDays(req.returnDays());
            }
            case MarketplacePolicy.COMMISSION -> {
                CommissionPolicy.Basis basis = basis(req.commissionBasis());
                try {
                    new CommissionPolicy(basis, req.commissionRate(), req.commissionFixed());   // validates the terms
                } catch (MarketplaceRuleException e) {
                    throw new ValidationException(e.getMessage());
                }
                p.setCommissionBasis(basis.name());
                p.setCommissionRate(basis == CommissionPolicy.Basis.FIXED ? null : req.commissionRate());
                p.setCommissionFixed(basis == CommissionPolicy.Basis.FIXED ? req.commissionFixed() : null);
                if (Boolean.TRUE.equals(req.isDefault())) {
                    // one default at a time: the previous default stops being the default, its terms unchanged
                    policies.findFirstByPolicyTypeAndActiveTrueAndIsDefaultTrue(MarketplacePolicy.COMMISSION)
                            .ifPresent(old -> { old.setIsDefault(false); policies.save(old); });
                    p.setIsDefault(true);
                }
            }
            default -> throw new ValidationException("A policy is WARRANTY, RETURN or COMMISSION.");
        }
        OfferDTOs.Policy out = toDto(policies.save(p));
        audit.event("MKT_POLICY_CREATED", "MKT_POLICY", String.valueOf(out.id()), null, MarketplaceAuditService.Actor.OPERATOR,
                null, type, null, name);
        return out;
    }

    @Transactional
    public OfferDTOs.Policy deactivate(Long id) {
        access.assertOperator();
        MarketplacePolicy p = policies.findById(id).orElseThrow(() -> new ResourceNotFoundException("No such policy."));
        p.setActive(false);
        p.setIsDefault(false);
        OfferDTOs.Policy out = toDto(policies.save(p));
        audit.event("MKT_POLICY_DEACTIVATED", "MKT_POLICY", String.valueOf(id), null, MarketplaceAuditService.Actor.OPERATOR,
                "ACTIVE", "INACTIVE", null, p.getName());
        return out;
    }

    @Transactional(readOnly = true)
    public List<OfferDTOs.Policy> all() {
        access.assertOperator();
        return policies.findAllByOrderByPolicyTypeAscNameAsc().stream().map(MarketplacePolicyService::toDto).toList();
    }

    /** What a seller may choose from: active WARRANTY and RETURN policies. Commission is never the seller's choice. */
    @Transactional(readOnly = true)
    public List<OfferDTOs.Policy> sellerChoices() {
        return java.util.stream.Stream.concat(
                policies.findByPolicyTypeAndActiveTrueOrderByNameAsc(MarketplacePolicy.WARRANTY).stream(),
                policies.findByPolicyTypeAndActiveTrueOrderByNameAsc(MarketplacePolicy.RETURN).stream())
                .map(MarketplacePolicyService::toDto).toList();
    }

    /** The policy an offer names, checked to be ACTIVE and of the expected type. */
    MarketplacePolicy usable(Long id, String type) {
        if (id == null) return null;
        MarketplacePolicy p = policies.findById(id).orElse(null);
        if (p == null || !type.equals(p.getPolicyType()) || !Boolean.TRUE.equals(p.getActive()))
            throw new ValidationException("Choose an active " + type.toLowerCase() + " policy.");
        return p;
    }

    MarketplacePolicy defaultCommission() {
        return policies.findFirstByPolicyTypeAndActiveTrueAndIsDefaultTrue(MarketplacePolicy.COMMISSION).orElseThrow(() ->
                new ValidationException("Set a default commission policy before approving offers."));
    }

    private static CommissionPolicy.Basis basis(String s) {
        try {
            return CommissionPolicy.Basis.valueOf(s == null ? "" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Commission basis is ITEMS, ITEMS_PLUS_DELIVERY or FIXED.");
        }
    }

    private static String clip(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    static OfferDTOs.Policy toDto(MarketplacePolicy p) {
        return new OfferDTOs.Policy(p.getId(), p.getPolicyType(), p.getName(), p.getActive(), p.getIsDefault(),
                p.getWarrantyProvider(), p.getWarrantyMonths(), p.getWarrantyStarts(), p.getWarrantyCovers(),
                p.getWarrantyExcludes(), p.getClaimProcess(), p.getReturnDays(), p.getCommissionBasis(),
                p.getCommissionRate(), p.getCommissionFixed());
    }
}
