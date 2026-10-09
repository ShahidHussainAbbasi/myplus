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
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceSellerService;
import com.myplus.marketplace.multiseller.service.PlatformWarehouseService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-0a — seller onboarding. Thin: every rule (capability, tier, agreement version, operator role, the lifecycle)
 * lives in {@link MarketplaceSellerService}, so the monolith proxy and any future caller meet the same guard.
 *
 * <pre>
 *   GET  /mkt/seller                              the caller's own seller status (any member)
 *   POST /mkt/seller/agreements                   accept both agreements + apply (owner/admin, capability ON)
 *   GET  /mkt/operator/sellers?status=&page=&size= the operator's queue (ROLE_ADMIN)
 *   POST /mkt/operator/sellers/{org}/decision     APPROVE | REJECT | SUSPEND | REINSTATE (ROLE_ADMIN)
 *   GET  /mkt/operator/warehouse                  MKT-3a: the MaxTheService warehouse and who could be it (ROLE_ADMIN)
 *   POST /mkt/operator/warehouse                  {organizationId}: name it; null removes it (ROLE_ADMIN)
 * </pre>
 */
@RestController
@RequestMapping("/mkt")
@RequiredArgsConstructor
public class MarketplaceSellerController {

    private final MarketplaceSellerService service;
    private final PlatformWarehouseService warehouse;

    @GetMapping("/seller")
    public ApiResponse<SellerDTOs.SellerView> mySeller() {
        return ApiResponse.success(service.view());
    }

    @PostMapping("/seller/agreements")
    public ApiResponse<SellerDTOs.Acceptance> accept(@RequestBody SellerDTOs.AcceptRequest body) {
        return ApiResponse.success(service.accept(body), "Agreements accepted. MaxTheService will review your seller account.");
    }

    @GetMapping("/operator/sellers")
    public ApiResponse<PageResponse<SellerDTOs.Account>> sellers(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.list(status, page, size));
    }

    @PostMapping("/operator/sellers/{organizationId}/decision")
    public ApiResponse<SellerDTOs.Account> decide(@PathVariable Long organizationId,
            @RequestBody SellerDTOs.DecisionRequest body) {
        SellerDTOs.Account a = service.decide(organizationId, body);
        return ApiResponse.success(a, "Seller account is now " + a.status().toLowerCase().replace('_', ' ') + ".");
    }

    @GetMapping("/operator/warehouse")
    public ApiResponse<SellerDTOs.Warehouse> warehouse() {
        return ApiResponse.success(warehouse.view());
    }

    @PostMapping("/operator/warehouse")
    public ApiResponse<SellerDTOs.Warehouse> setWarehouse(@RequestBody(required = false) SellerDTOs.WarehouseRequest body) {
        SellerDTOs.Warehouse w = warehouse.set(body);
        return ApiResponse.success(w, w.organizationId() == null ? "No warehouse: MaxTheService sells no stock of its own."
                : w.organizationName() + " is the MaxTheService warehouse. Its offers read \"Sold and shipped by MaxTheService\".");
    }
}
