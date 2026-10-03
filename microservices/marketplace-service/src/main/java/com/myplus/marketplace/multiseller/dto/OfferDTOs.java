package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** MKT-1c — offer, policy and public-offer wire shapes. */
public final class OfferDTOs {

    private OfferDTOs() {
    }

    /**
     * POST /mkt/offers (create when {@code id} is null, else edit). Party ids are NOT here on purpose: they are
     * stamped from the token. Any extra field a client sends (sellerOrganizationId, stockOwnerOrganizationId …) is
     * not bound and has no effect.
     */
    public record SaveRequest(Long id, Long mktProductId, String stockSourceType, String sellerSku,
            BigDecimal listPrice, BigDecimal marketplacePrice, String deliveryAreas, Integer promiseHours,
            Long warrantyPolicyId, Long returnPolicyId, Boolean paused, Integer version) {
    }

    public record Offer(Long id, Long organizationId, Long mktProductId, String productName, Long sourceProductId,
            String stockSourceType, Long stockOwnerOrganizationId, Long custodianOrganizationId,
            Long sellerOrganizationId, Long fulfillerOrganizationId, String sellerSku, BigDecimal listPrice,
            BigDecimal marketplacePrice, String deliveryAreas, Integer promiseHours, Long warrantyPolicyId,
            Long returnPolicyId, Long commissionPolicyId, String approvalStatus, Boolean paused, String reviewNote,
            LocalDateTime publishedAt, LocalDateTime createdAt, Integer version) {
    }

    /** POST /mkt/operator/offers/{id}/decision. decision: APPROVE | REJECT | SUSPEND | REINSTATE. */
    public record DecisionRequest(String decision, String note, Integer version) {
    }

    /** POST /mkt/operator/policies — creates a new policy (policies are never edited). */
    public record PolicyRequest(String policyType, String name, Boolean isDefault, String warrantyProvider,
            Integer warrantyMonths, String warrantyCovers, String warrantyExcludes, String claimProcess,
            Integer returnDays, String commissionBasis, BigDecimal commissionRate, BigDecimal commissionFixed) {
    }

    public record Policy(Long id, String policyType, String name, Boolean active, Boolean isDefault,
            String warrantyProvider, Integer warrantyMonths, String warrantyStarts, String warrantyCovers,
            String warrantyExcludes, String claimProcess, Integer returnDays, String commissionBasis,
            BigDecimal commissionRate, BigDecimal commissionFixed) {
    }

    /** POST /mkt/operator/products/{id}/limits. */
    public record LimitsRequest(BigDecimal priceFloor, BigDecimal priceCeiling) {
    }

    /**
     * One offer as a CUSTOMER sees it (source §7 "offer display"): §9.2 fields only. No cost, no margin, no
     * stock history, no organisation internals beyond the seller's own display name and id.
     */
    public record PublicOffer(Long offerId, Long mktProductId, Long sellerOrganizationId, String sellerName,
            BigDecimal price, BigDecimal availableQty, Integer promiseHours, BigDecimal rating,
            Integer warrantyMonths, String warrantyProvider, String warrantyStartsOn, String warrantyCovers,
            String warrantyExcludes, Integer returnDays, List<String> deliveryAreas, LocalDateTime lastSyncAt) {
    }
}
