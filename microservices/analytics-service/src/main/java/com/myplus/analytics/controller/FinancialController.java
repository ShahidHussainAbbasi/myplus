package com.myplus.analytics.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.analytics.dto.FinancialSummaryDTO;
import com.myplus.analytics.dto.FinancialTrendDTO;
import com.myplus.analytics.dto.MetricDTO;
import com.myplus.analytics.service.FinancialAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The finance figures (AN-1). Every endpoint carries finance's statements rule: analytics may answer from its store
 * when finance is down, so finance's own check cannot be the only gate.
 */
@RestController
@RequestMapping("/api/analytics/financial")
@RequiredArgsConstructor
public class FinancialController {

    /** The same rule as finance's GlController.STATEMENTS. */
    static final String STATEMENTS = "hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')";

    private final FinancialAnalyticsService financialService;

    /** AN-1 — the P&L month by month ({@code from}/{@code to} as yyyy-MM; default the last 12 months). */
    @PreAuthorize(STATEMENTS)
    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<FinancialTrendDTO>> monthly(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth to) {
        return ResponseEntity.ok(ApiResponse.success(financialService.monthly(from, to)));
    }

    @PreAuthorize(STATEMENTS)
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<FinancialSummaryDTO>> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(financialService.getFinancialSummary(startDate, endDate)));
    }

    @PreAuthorize(STATEMENTS)
    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<List<MetricDTO>>> revenue(
            @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok(ApiResponse.success(financialService.getRevenueByPeriod(months)));
    }
}
