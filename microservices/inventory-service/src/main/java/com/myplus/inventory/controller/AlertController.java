package com.myplus.inventory.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.inventory.entity.StockAlert;
import com.myplus.inventory.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<StockAlert>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(alertService.getUnreadAlerts()));
    }

    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<List<StockAlert>>> unread() {
        return ResponseEntity.ok(ApiResponse.success(alertService.getUnreadAlerts()));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<ApiResponse<StockAlert>> markRead(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(alertService.markRead(id), "Marked as read"));
    }

    @PutMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead() {
        int count = alertService.markAllRead();
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", count), "All marked as read"));
    }
}
