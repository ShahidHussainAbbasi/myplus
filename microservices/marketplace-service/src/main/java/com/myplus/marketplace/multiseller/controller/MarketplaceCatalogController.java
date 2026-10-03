package com.myplus.marketplace.multiseller.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.CatalogDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceCatalogService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1b — product proposals and match review. Every rule lives in {@link MarketplaceCatalogService}.
 *
 * <pre>
 *   POST /mkt/products/propose                     seller (active seller: capability + agreements + APPROVED)
 *   GET  /mkt/products/proposals?page=&size=       seller's own proposals
 *   GET  /mkt/operator/matches?status=&page=&size= operator queue (ROLE_ADMIN)
 *   POST /mkt/operator/matches/{id}/decision       MATCHED | NEEDS_CORRECTION | REJECTED (ROLE_ADMIN)
 *   GET  /mkt/operator/products?q=&page=&size=     canonical products (ROLE_ADMIN)
 * </pre>
 */
@RestController
@RequestMapping("/mkt")
@RequiredArgsConstructor
public class MarketplaceCatalogController {

    private final MarketplaceCatalogService service;

    @PostMapping("/products/propose")
    public ApiResponse<CatalogDTOs.Proposal> propose(@RequestBody CatalogDTOs.ProposeRequest body) {
        return ApiResponse.success(service.propose(body), "Sent to MaxTheService for review.");
    }

    @GetMapping("/products/proposals")
    public ApiResponse<PageResponse<CatalogDTOs.Proposal>> proposals(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.myProposals(page, size));
    }

    @GetMapping("/operator/matches")
    public ApiResponse<PageResponse<CatalogDTOs.Proposal>> queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.queue(status, page, size));
    }

    @PostMapping("/operator/matches/{id}/decision")
    public ApiResponse<CatalogDTOs.Proposal> decide(@PathVariable Long id, @RequestBody CatalogDTOs.DecisionRequest body) {
        CatalogDTOs.Proposal p = service.decide(id, body);
        return ApiResponse.success(p, "Proposal is now " + p.matchStatus().toLowerCase().replace('_', ' ') + ".");
    }

    @GetMapping("/operator/products")
    public ApiResponse<PageResponse<CatalogDTOs.Product>> products(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.canonicalProducts(q, page, size));
    }
}
