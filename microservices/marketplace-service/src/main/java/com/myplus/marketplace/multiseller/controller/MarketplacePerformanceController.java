package com.myplus.marketplace.multiseller.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.multiseller.dto.PerformanceDTOs;
import com.myplus.marketplace.multiseller.service.SellerPerformanceService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2e — the seller scorecard. Read-only: a flag is a record, never a sanction (R12.4).
 *
 * <pre>
 *   GET /mkt/seller/performance?days=7|30|90       seller: its own scorecard
 *   GET /mkt/operator/performance?days=7|30|90     operator: every seller with an order in the window, flagged first
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplacePerformanceController {

    private final SellerPerformanceService performance;

    @GetMapping("/mkt/seller/performance")
    public ApiResponse<PerformanceDTOs.PerformanceView> mine(@RequestParam(required = false) Integer days) {
        return ApiResponse.success(performance.mine(days));
    }

    @GetMapping("/mkt/operator/performance")
    public ApiResponse<PerformanceDTOs.PerformanceView> operator(@RequestParam(required = false) Integer days) {
        return ApiResponse.success(performance.operator(days));
    }
}
