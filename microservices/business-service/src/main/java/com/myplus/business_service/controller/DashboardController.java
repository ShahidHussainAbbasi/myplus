package com.myplus.business_service.controller;

import com.myplus.business_service.dto.ApiResponse;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/business/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ApiResponse<?> getStats(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(dashboardService.getStats(user.getUserId()));
    }

    @GetMapping("/charts")
    public ApiResponse<?> getCharts(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(dashboardService.getCharts(user.getUserId()));
    }
}
