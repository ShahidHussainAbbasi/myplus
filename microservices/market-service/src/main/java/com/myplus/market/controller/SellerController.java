package com.myplus.market.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.common.web.ApiResponse;
import com.myplus.market.dto.MarketDtos.ApplyRequest;
import com.myplus.market.dto.MarketDtos.PolicyView;
import com.myplus.market.dto.MarketDtos.SellerView;
import com.myplus.market.service.SellerOnboardingService;

import lombok.RequiredArgsConstructor;

/**
 * MP-0b seller API. The seller is always the caller's own org (token); nothing here accepts an organisation id.
 * Reading is open to any member of the business; applying commits the business, so it is owner/admin.
 */
@RestController
@RequestMapping("/api/market/seller")
@RequiredArgsConstructor
public class SellerController {

    private final SellerOnboardingService sellers;

    @GetMapping("/policies/current")
    public ApiResponse<List<PolicyView>> currentPolicies() {
        return ApiResponse.success(sellers.policiesToAccept());
    }

    @GetMapping("/profile")
    public ApiResponse<SellerView> profile() {
        return ApiResponse.success(sellers.myProfile());
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/apply")
    public ApiResponse<SellerView> apply(@RequestBody ApplyRequest r) {
        return ApiResponse.success(sellers.apply(r), "Application sent — MaxTheService will review it");
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/withdraw")
    public ApiResponse<SellerView> withdraw(@RequestBody(required = false) com.myplus.market.dto.MarketDtos.ReasonRequest r) {
        return ApiResponse.success(sellers.withdraw(r == null ? null : r.reason()), "You have left the marketplace");
    }
}
