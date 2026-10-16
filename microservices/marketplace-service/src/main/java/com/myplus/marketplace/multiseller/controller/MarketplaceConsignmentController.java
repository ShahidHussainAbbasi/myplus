package com.myplus.marketplace.multiseller.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.multiseller.dto.ConsignmentDTOs;
import com.myplus.marketplace.multiseller.service.ConsignmentAgreementService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-5a — consignment agreements. Rules live in {@link ConsignmentAgreementService}.
 *
 * <pre>
 *   GET  /mkt/operator/consignments?status=          the operator's list (ROLE_ADMIN)
 *   POST /mkt/operator/consignments                  {consignorOrganizationId, terms, note}
 *   POST /mkt/operator/consignments/decision         {id, action: WITHDRAW | END, version, reason}
 *   GET  /mkt/seller/consignments                    a business's own agreements (consignor or the warehouse)
 *   POST /mkt/seller/consignments/decision           the consignor: {id, action: ACCEPT | DECLINE | END, version, reason}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceConsignmentController {

    private final ConsignmentAgreementService agreements;

    @GetMapping("/mkt/operator/consignments")
    public ApiResponse<List<ConsignmentDTOs.Agreement>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(agreements.list(status));
    }

    @PostMapping("/mkt/operator/consignments")
    public ApiResponse<ConsignmentDTOs.Agreement> offer(@RequestBody(required = false) ConsignmentDTOs.OfferRequest body) {
        ConsignmentDTOs.Agreement a = agreements.offer(body);
        return ApiResponse.success(a, "Offered to " + a.consignorName() + " as " + a.agreementNo()
                + ". Nothing can be consigned until they accept.");
    }

    @PostMapping("/mkt/operator/consignments/decision")
    public ApiResponse<ConsignmentDTOs.Agreement> operatorDecision(@RequestBody(required = false) ConsignmentDTOs.DecisionRequest body) {
        ConsignmentDTOs.Agreement a = agreements.decide(body, true);
        return ApiResponse.success(a, said(a));
    }

    @GetMapping("/mkt/seller/consignments")
    public ApiResponse<ConsignmentDTOs.View> mine() {
        return ApiResponse.success(agreements.mine());
    }

    @PostMapping("/mkt/seller/consignments/decision")
    public ApiResponse<ConsignmentDTOs.Agreement> sellerDecision(@RequestBody(required = false) ConsignmentDTOs.DecisionRequest body) {
        ConsignmentDTOs.Agreement a = agreements.decide(body, false);
        return ApiResponse.success(a, said(a));
    }

    static String said(ConsignmentDTOs.Agreement a) {
        return switch (a.status()) {
            case "ACTIVE" -> a.agreementNo() + " is active. Stock can be consigned to MaxTheService on these terms.";
            case "DECLINED" -> a.agreementNo() + " is declined. MaxTheService sees your reason.";
            case "WITHDRAWN" -> a.agreementNo() + " is withdrawn.";
            case "ENDED" -> a.agreementNo() + " has ended. No new stock can be consigned under it.";
            default -> a.agreementNo() + " is " + a.status().toLowerCase(java.util.Locale.ROOT) + ".";
        };
    }
}
