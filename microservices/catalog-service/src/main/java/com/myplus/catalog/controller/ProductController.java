package com.myplus.catalog.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.common.security.CurrentUser;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.catalog.service.ProductImportService;
import com.myplus.catalog.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/catalog/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductImportService productImportService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductDTO>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(productService.getAll(pageable), p -> p)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTO>> create(@RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.create(dto), "Created"));
    }

    /**
     * Bulk import for the item→product migration (slice 33, U2). Returns the clientRef→productId map.
     * Raw (un-wrapped) response for the inter-service caller — matches {@code CatalogClient.importProducts}
     * and the same raw convention as {@code /{id}/ref}.
     */
    @PostMapping("/import")
    public List<ProductImportResult> importProducts(@RequestBody List<ProductImportLine> items) {
        return productImportService.importProducts(items, CurrentUser.organizationId(), CurrentUser.userId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.getById(id)));
    }

    /** Raw ProductRef (+ price) for inter-service callers (sell saga, slice 33 U3b) — matches CatalogClient. */
    @GetMapping("/{id}/ref")
    public com.myplus.commerce.contracts.dto.ProductRef getRef(@PathVariable Long id) {
        return productService.getRef(id);
    }

    /** M4d (slice 93): batch refs by id for the POS read screens — GET /products/refs?ids=1,2,3 (tenant-scoped). */
    @GetMapping("/refs")
    public java.util.List<com.myplus.commerce.contracts.dto.ProductRef> getRefs(
            @org.springframework.web.bind.annotation.RequestParam java.util.List<Long> ids) {
        return productService.getRefs(ids);
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

    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<ProductDTO>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.setActive(id, true)));
    }

    @PutMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<ProductDTO>> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(productService.setActive(id, false)));
    }
}
