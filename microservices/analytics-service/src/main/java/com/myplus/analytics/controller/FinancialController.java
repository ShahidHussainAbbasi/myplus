package com.myplus.analytics.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.analytics.dto.FinancialSummaryDTO;
import com.myplus.analytics.dto.MetricDTO;
import com.myplus.analytics.service.FinancialAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/financial")
@RequiredArgsConstructor
public class FinancialController {

    private final FinancialAnalyticsService financialService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<FinancialSummaryDTO>> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(financialService.getFinancialSummary(startDate, endDate)));
    }

    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<List<MetricDTO>>> revenue(
            @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(ApiResponse.success(financialService.getRevenueByPeriod(months)));
    }
}
