package com.myplus.analytics.controller;

import com.myplus.analytics.dto.ApiResponse;
import com.myplus.analytics.dto.DashboardWidgetDTO;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.analytics.service.DashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analytics/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/widgets")
    public ResponseEntity<ApiResponse<List<DashboardWidgetDTO>>> getWidgets(
            @AuthenticationPrincipal AuthenticatedUser user) {
        Long userId = user != null ? user.getUserId() : 0L;
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getUserWidgets(userId)));
    }

    @PostMapping("/widgets")
    public ResponseEntity<ApiResponse<DashboardWidgetDTO>> addWidget(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DashboardWidgetDTO dto) {
        Long userId = user != null ? user.getUserId() : 0L;
        return ResponseEntity.ok(ApiResponse.success(dashboardService.addWidget(userId, dto)));
    }

    @PutMapping("/widgets/{id}")
    public ResponseEntity<ApiResponse<DashboardWidgetDTO>> updateWidget(
            @PathVariable Long id, @Valid @RequestBody DashboardWidgetDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.updateWidget(id, dto)));
    }

    @DeleteMapping("/widgets/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteWidget(@PathVariable Long id) {
        dashboardService.removeWidget(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Widget removed"));
    }

    @PutMapping("/widgets/reorder")
    public ResponseEntity<ApiResponse<Void>> reorder(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody List<Long> widgetIds) {
        Long userId = user != null ? user.getUserId() : 0L;
        dashboardService.reorderWidgets(userId, widgetIds);
        return ResponseEntity.ok(ApiResponse.success(null, "Widgets reordered"));
    }
}
