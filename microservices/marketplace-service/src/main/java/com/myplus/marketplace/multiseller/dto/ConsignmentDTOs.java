package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** MKT-5a — consignment agreements: the terms (R4.6, R12.2), offered by the operator, answered by the consignor. */
public final class ConsignmentDTOs {

    private ConsignmentDTOs() { }

    /**
     * Every term, all required. {@code ownershipTransfer} ON_SALE | ON_DELIVERY; {@code priceSetBy} and the bearers
     * CONSIGNOR | CONSIGNEE; {@code insuredBy} also NONE. Percentages and days are whole bounded numbers (see the
     * service for the bounds, which its refusals name).
     */
    public record Terms(String ownershipTransfer, String priceSetBy, BigDecimal commissionPct, Integer settleEveryDays,
                        Integer reconcileEveryDays, Integer countEveryDays, BigDecimal shrinkageTolerancePct,
                        String damageBearer, String expiryBearer, String insuredBy, Integer claimWindowDays,
                        Boolean proofRequired, Integer unsoldReturnDays, String unsoldReturnBearer) { }

    /** The operator offers terms to a consignor; the consignee is the MaxTheService warehouse. */
    public record OfferRequest(Long consignorOrganizationId, Terms terms, String note) { }

    /** Withdraw or End (the operator); Accept, Decline or End (the consignor). A reason is needed to decline or end. */
    public record DecisionRequest(Long id, String action, Integer version, String reason) { }

    public record Agreement(Long id, String agreementNo, Long consignorOrganizationId, String consignorName,
                            Long consigneeOrganizationId, String consigneeName, String status, Terms terms, String note,
                            String answerReason, String replacedByNo, LocalDateTime createdAt, LocalDateTime answeredAt,
                            LocalDateTime endedAt, Integer version) { }

    /** What a business sees: whether it is the warehouse, and its agreements either way. */
    public record View(boolean warehouse, List<Agreement> agreements) { }
}
