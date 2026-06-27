package com.myplus.inventory.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.inventory.dto.SupplierDTO;
import com.myplus.inventory.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SupplierDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(supplierService.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SupplierDTO>> create(@RequestBody SupplierDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(supplierService.create(dto), "Created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(supplierService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SupplierDTO>> update(@PathVariable Long id, @RequestBody SupplierDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(supplierService.update(id, dto), "Updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted"));
    }

    @GetMapping("/{id}/products")
    public ResponseEntity<ApiResponse<List<Object>>> products(@PathVariable Long id) {
        // Stock entries linked to this supplier represent products supplied
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }
}
