package com.myplus.market.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MP-0b wire types. Note what is ABSENT: no request carries an organisation id. The seller is the caller's org
 * from the token; the platform org is configuration.
 */
public final class MarketDtos {

    private MarketDtos() {}

    public record PolicyRequest(String policyType, String title, String summary, String documentUrl,
                                LocalDate effectiveFrom) {}

    public record PolicyView(Long id, String policyType, Integer versionNo, String title, String summary,
                             String documentUrl, String status, LocalDate effectiveFrom,
                             LocalDateTime publishedAt, boolean sellerMustAccept) {}

    public record ApplyRequest(String displayName, String contactPhone, String contactEmail, String city,
                               String pickupAddress, Integer serviceRadiusKm, List<Long> acceptedPolicyIds) {}

    public record ReasonRequest(String reason) {}

    public record AgreementView(String policyType, Integer versionNo, Long policyId, LocalDateTime acceptedAt) {}

    public record SellerView(Long id, Long sellerOrganizationId, String displayName, String contactPhone,
                             String contactEmail, String city, String pickupAddress, Integer serviceRadiusKm,
                             String status, String statusReason, LocalDateTime appliedAt,
                             LocalDateTime reviewedAt, List<AgreementView> agreements) {}
}
