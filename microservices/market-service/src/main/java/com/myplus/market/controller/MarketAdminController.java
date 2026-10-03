package com.myplus.market.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.common.web.ApiResponse;
import com.myplus.market.dto.MarketDtos.PolicyRequest;
import com.myplus.market.dto.MarketDtos.PolicyView;
import com.myplus.market.dto.MarketDtos.ReasonRequest;
import com.myplus.market.dto.MarketDtos.SellerView;
import com.myplus.market.service.PolicyService;
import com.myplus.market.service.SellerOnboardingService;

import lombok.RequiredArgsConstructor;

/**
 * MP-0b operator API. {@code ROLE_ADMIN} only — the platform operator — at class level, and again inside every
 * service method ({@code MarketAccess.assertOperator}). A tenant owner's {@code ADMIN_PRIVILEGE} is not enough.
 *
 * <p>Command style: each state change is its own endpoint, never a status field in a PATCH, so each carries its
 * own validation and audit action.
 */
@RestController
@RequestMapping("/api/market/admin")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class MarketAdminController {

    private final PolicyService policies;
    private final SellerOnboardingService sellers;

    @GetMapping("/policies")
    public ApiResponse<List<PolicyView>> policies(@RequestParam(required = false) String type) {
        return ApiResponse.success(policies.list(type));
    }

    @PostMapping("/policies")
    public ApiResponse<PolicyView> createPolicy(@RequestBody PolicyRequest r) {
        return ApiResponse.success(policies.create(r), "Draft saved");
    }

    @PostMapping("/policies/{id}/publish")
    public ApiResponse<PolicyView> publish(@PathVariable Long id) {
        return ApiResponse.success(policies.publish(id), "Policy published");
    }

    @GetMapping("/sellers")
    public ApiResponse<List<SellerView>> sellers(@RequestParam(required = false) String status) {
        return ApiResponse.success(sellers.list(status));
    }

    @PostMapping("/sellers/{id}/approve")
    public ApiResponse<SellerView> approve(@PathVariable Long id) {
        return ApiResponse.success(sellers.approve(id), "Seller approved");
    }

    @PostMapping("/sellers/{id}/reject")
    public ApiResponse<SellerView> reject(@PathVariable Long id, @RequestBody(required = false) ReasonRequest r) {
        return ApiResponse.success(sellers.reject(id, r == null ? null : r.reason()), "Application rejected");
    }

    @PostMapping("/sellers/{id}/suspend")
    public ApiResponse<SellerView> suspend(@PathVariable Long id, @RequestBody(required = false) ReasonRequest r) {
        return ApiResponse.success(sellers.suspend(id, r == null ? null : r.reason()), "Seller suspended");
    }

    @PostMapping("/sellers/{id}/reinstate")
    public ApiResponse<SellerView> reinstate(@PathVariable Long id) {
        return ApiResponse.success(sellers.reinstate(id), "Seller reinstated");
    }
}
