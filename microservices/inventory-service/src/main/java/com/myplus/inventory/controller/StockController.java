package com.myplus.inventory.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.common.security.CurrentUser;
import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.inventory.dto.StockDTOs.*;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.service.StockImportService;
import com.myplus.inventory.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final StockImportService stockImportService;

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

    /** Raw current on-hand for inter-service callers (trade UI, slice 33 U4) — matches InventoryClient.getStockLevel. */
    @GetMapping("/level/{productId}")
    public Float stockLevel(@PathVariable Long productId) {
        return stockService.getCurrentStock(productId);
    }

    /** FEFO batches (batch/expiry + sellable qty) for the dispense/sell screen (slice 54, P10). Raw body so the
     *  trade-service InventoryClient.getBatches deserializes it directly. */
    @GetMapping("/batches/{productId}")
    public java.util.List<com.myplus.commerce.contracts.dto.StockBatch> batches(@PathVariable Long productId) {
        return stockService.getFefoBatches(productId);
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

    /** Bulk opening-stock seed for the item→product migration (slice 33, U2b). Returns the count created.
     *  Raw body (not ApiResponse) so trade-service's InventoryClient.importStock deserializes it directly. */
    @PostMapping("/import")
    public Integer importStock(@RequestBody List<StockImportLine> lines) {
        return stockImportService.importStock(lines, CurrentUser.organizationId(), CurrentUser.userId());
    }
}
