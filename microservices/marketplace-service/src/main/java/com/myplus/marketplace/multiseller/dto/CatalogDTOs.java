package com.myplus.marketplace.multiseller.dto;

import java.time.LocalDateTime;

/** MKT-1b — product matching wire shapes. */
public final class CatalogDTOs {

    private CatalogDTOs() {
    }

    /**
     * POST /mkt/products/propose. The identity attributes are the seller's declaration. {@code identityKey}, if a
     * client sends one, is ignored: the key is always computed on the server (MKT-R6.2).
     */
    public record ProposeRequest(Long sourceProductId, String brand, String model, String variant, String colour,
            String size, String unit, String packSize, String condition, String warrantyType, String gtin) {
    }

    public record Proposal(Long id, Long organizationId, Long sourceProductId, String sourceProductName,
            String proposedIdentityKey, String brand, String model, String variant, String colour, String size,
            String unit, String packSize, String condition, String warrantyType, String gtin, String regulated,
            String matchStatus, Long suggestedProductId, Long mktProductId, String reviewNote,
            LocalDateTime createdAt, LocalDateTime reviewedAt, Integer version) {
    }

    /**
     * POST /mkt/operator/matches/{id}/decision. decision: MATCHED | NEEDS_CORRECTION | REJECTED.
     * For MATCHED, {@code mktProductId} picks the canonical product; absent, the suggestion is used, or a new
     * canonical product is created from the proposal. {@code note} is required for the other two.
     */
    public record DecisionRequest(String decision, Long mktProductId, String note, Integer version) {
    }

    public record Product(Long id, String identityKey, String canonicalName, String brand, String model,
            String variant, String colour, String size, String unit, String packSize, String condition,
            String warrantyType, String gtin, String regulated, String approval) {
    }
}
