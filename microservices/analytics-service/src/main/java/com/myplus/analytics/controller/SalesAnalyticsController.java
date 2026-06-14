package com.myplus.analytics.controller;

import com.myplus.analytics.dto.ApiResponse;
import com.myplus.analytics.dto.MetricDTO;
import com.myplus.analytics.dto.SalesAnalyticsDTO;
import com.myplus.analytics.service.SalesAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analytics/sales")
@RequiredArgsConstructor
public class SalesAnalyticsController {

    private final SalesAnalyticsService salesService;

    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<List<SalesAnalyticsDTO>>> trend(
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(ApiResponse.success(salesService.getSalesTrend(months)));
    }

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<MetricDTO>>> daily(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.success(salesService.getDailySales(year, month)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<List<MetricDTO>>> summary() {
        return ResponseEntity.ok(ApiResponse.success(salesService.getTopMetrics()));
    }
}
