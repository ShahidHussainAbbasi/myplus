package com.myplus.inventory.controller;

import com.myplus.inventory.dto.ApiResponse;
import com.myplus.inventory.dto.PageResponse;
import com.myplus.inventory.dto.WarehouseDTO;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<WarehouseDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(warehouseService.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WarehouseDTO>> create(@RequestBody WarehouseDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(warehouseService.create(dto), "Created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WarehouseDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(warehouseService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WarehouseDTO>> update(@PathVariable Long id, @RequestBody WarehouseDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(warehouseService.update(id, dto), "Updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        warehouseService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted"));
    }

    @GetMapping("/{id}/stock")
    public ResponseEntity<ApiResponse<PageResponse<StockEntry>>> stock(@PathVariable Long id, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(warehouseService.getStock(id, pageable), e -> e)));
    }
}
