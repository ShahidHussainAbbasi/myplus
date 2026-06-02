package com.myplus.inventory.controller;

import com.myplus.inventory.dto.ApiResponse;
import com.myplus.inventory.dto.PageResponse;
import com.myplus.inventory.dto.StockDTOs.*;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/add")
    public ResponseEntity<ApiResponse<StockEntry>> addStock(@RequestBody StockEntryDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(stockService.addStock(dto), "Stock added"));
    }

    @PostMapping("/adjust")
    public ResponseEntity<ApiResponse<?>> adjust(@RequestBody StockAdjustmentDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(stockService.adjustStock(dto), "Stock adjusted"));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<?>> transfer(@RequestBody StockTransferDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(stockService.transferStock(dto), "Stock transferred"));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<Float>> current(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success(stockService.getCurrentStock(productId)));
    }

    @GetMapping("/{productId}/history")
    public ResponseEntity<ApiResponse<PageResponse<StockEntry>>> history(@PathVariable Long productId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(stockService.getHistory(productId, pageable), e -> e)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<StockSummaryDTO>> summary() {
        return ResponseEntity.ok(ApiResponse.success(stockService.getSummary()));
    }
}
