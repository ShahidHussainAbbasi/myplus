package com.myplus.inventory.controller;

import com.myplus.inventory.dto.ApiResponse;
import com.myplus.inventory.dto.PageResponse;
import com.myplus.inventory.dto.ProductDTO;
import com.myplus.inventory.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/inventory/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductDTO>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(productService.getAll(pageable), p -> p)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTO>> create(@RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.create(dto), "Created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTO>> update(@PathVariable Long id, @RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.update(id, dto), "Updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted"));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<ProductDTO>>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(productService.search(q, category, minPrice, maxPrice, pageable), p -> p)));
    }

    @GetMapping("/low-stock")
    public ResponseEntity<ApiResponse<List<ProductDTO>>> lowStock() {
        return ResponseEntity.ok(ApiResponse.success(productService.getLowStock()));
    }

    @GetMapping("/out-of-stock")
    public ResponseEntity<ApiResponse<List<ProductDTO>>> outOfStock() {
        return ResponseEntity.ok(ApiResponse.success(productService.getOutOfStock()));
    }

    @GetMapping("/expiring")
    public ResponseEntity<ApiResponse<List<ProductDTO>>> expiring(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(productService.getExpiring(days)));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<ProductDTO>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.setActive(id, true)));
    }

    @PutMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<ProductDTO>> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.setActive(id, false)));
    }
}
