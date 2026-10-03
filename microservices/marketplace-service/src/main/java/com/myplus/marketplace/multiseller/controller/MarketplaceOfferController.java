package com.myplus.marketplace.multiseller.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceOfferService;
import com.myplus.marketplace.multiseller.service.MarketplacePolicyService;
import com.myplus.marketplace.multiseller.service.PublicOfferService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1c — offers, policies and the public offer read. Rules live in the services.
 *
 * <pre>
 *   POST /mkt/offers                                seller: create / edit (party ids stamped from the token)
 *   POST /mkt/offers/{id}/submit                    seller: send for approval
 *   GET  /mkt/offers?page=&size= · /mkt/offers/{id} seller: own offers (another tenant's id → 404)
 *   GET  /mkt/policies                              seller: active warranty + return policies to choose from
 *   GET  /mkt/operator/offers?status=               operator queue
 *   POST /mkt/operator/offers/{id}/decision         APPROVE | REJECT | SUSPEND | REINSTATE
 *   GET/POST /mkt/operator/policies · POST /mkt/operator/policies/{id}/deactivate
 *   POST /mkt/operator/products/{id}/limits         price floor / ceiling
 *   GET  /public/mkt/products/{id}/offers?city=&sort=&qty=   anonymous (gateway allow-lists /api/marketplace/public/)
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceOfferController {

    private final MarketplaceOfferService offers;
    private final MarketplacePolicyService policies;
    private final PublicOfferService publicOffers;

    @PostMapping("/mkt/offers")
    public ApiResponse<OfferDTOs.Offer> save(@RequestBody OfferDTOs.SaveRequest body) {
        return ApiResponse.success(offers.save(body), "Offer saved.");
    }

    @PostMapping("/mkt/offers/{id}/submit")
    public ApiResponse<OfferDTOs.Offer> submit(@PathVariable Long id) {
        return ApiResponse.success(offers.submit(id), "Sent to MaxTheService for approval.");
    }

    @GetMapping("/mkt/offers")
    public ApiResponse<PageResponse<OfferDTOs.Offer>> mine(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(offers.mine(page, size));
    }

    @GetMapping("/mkt/offers/{id}")
    public ApiResponse<OfferDTOs.Offer> one(@PathVariable Long id) {
        return ApiResponse.success(offers.one(id));
    }

    @GetMapping("/mkt/policies")
    public ApiResponse<List<OfferDTOs.Policy>> sellerPolicies() {
        return ApiResponse.success(policies.sellerChoices());
    }

    @GetMapping("/mkt/operator/offers")
    public ApiResponse<PageResponse<OfferDTOs.Offer>> queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(offers.queue(status, page, size));
    }

    @PostMapping("/mkt/operator/offers/{id}/decision")
    public ApiResponse<OfferDTOs.Offer> decide(@PathVariable Long id, @RequestBody OfferDTOs.DecisionRequest body) {
        OfferDTOs.Offer o = offers.decide(id, body);
        return ApiResponse.success(o, "Offer is now " + o.approvalStatus().toLowerCase().replace('_', ' ') + ".");
    }

    @GetMapping("/mkt/operator/policies")
    public ApiResponse<List<OfferDTOs.Policy>> allPolicies() {
        return ApiResponse.success(policies.all());
    }

    @PostMapping("/mkt/operator/policies")
    public ApiResponse<OfferDTOs.Policy> createPolicy(@RequestBody OfferDTOs.PolicyRequest body) {
        return ApiResponse.success(policies.create(body), "Policy created.");
    }

    @PostMapping("/mkt/operator/policies/{id}/deactivate")
    public ApiResponse<OfferDTOs.Policy> deactivate(@PathVariable Long id) {
        return ApiResponse.success(policies.deactivate(id), "Policy deactivated.");
    }

    @PostMapping("/mkt/operator/products/{id}/limits")
    public ApiResponse<Void> limits(@PathVariable Long id, @RequestBody OfferDTOs.LimitsRequest body) {
        offers.setLimits(id, body);
        return ApiResponse.success(null, "Price limits saved.");
    }

    @GetMapping("/public/mkt/products/{id}/offers")
    public ApiResponse<List<OfferDTOs.PublicOffer>> publicOffers(@PathVariable Long id,
            @RequestParam(required = false) String city, @RequestParam(required = false) String sort,
            @RequestParam(required = false) BigDecimal qty) {
        return ApiResponse.success(publicOffers.offers(id, city, sort, qty));
    }
}
