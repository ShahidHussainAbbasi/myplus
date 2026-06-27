package com.myplus.analytics.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.analytics.dto.ReportDefinitionDTO;
import com.myplus.analytics.dto.ReportExecutionDTO;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.analytics.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.function.Function;

@RestController
@RequestMapping("/api/analytics/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReportDefinitionDTO>> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ReportDefinitionDTO dto) {
        if (user != null) dto.setCreatedBy(user.getUserId());
        return ResponseEntity.ok(ApiResponse.success(reportService.createReport(dto)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ReportDefinitionDTO>>> getAll(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Long userId = user != null ? user.getUserId() : null;
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(reportService.getAll(userId, pageable), Function.identity())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReportDefinitionDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ReportDefinitionDTO>> update(
            @PathVariable Long id, @Valid @RequestBody ReportDefinitionDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(reportService.updateReport(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        reportService.deleteReport(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Report deleted"));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<ReportExecutionDTO>> execute(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.executeReport(id)));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<PageResponse<ReportExecutionDTO>>> getExecutions(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(reportService.getExecutions(id, pageable), Function.identity())));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<ApiResponse<ReportExecutionDTO>> getExecution(@PathVariable Long executionId) {
        return ResponseEntity.ok(ApiResponse.success(reportService.getExecutionById(executionId)));
    }
}
