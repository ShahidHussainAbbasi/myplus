package com.myplus.catalog.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.common.security.CurrentUser;
import com.myplus.catalog.dto.NameCheckDTO;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.catalog.service.ProductImportService;
import com.myplus.catalog.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // Slice 106: @Valid enforces ProductDTO's constraints. Without it the annotations are inert decoration —
    // the DTO carried none and this carried no @Valid, so a nameless product saved happily.
    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTO>> create(@Valid @RequestBody ProductDTO dto) {
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

    /** Barcode-first sell: resolve a scanned code (barcode or sku) to a ProductRef — GET /products/lookup?code=X.
     *  404 when nothing matches (tenant-scoped, active only). */
    @GetMapping("/lookup")
    public com.myplus.commerce.contracts.dto.ProductRef lookup(@RequestParam("code") String code) {
        return productService.lookup(code);
    }

    /** M4d (slice 93): batch refs by id for the POS read screens — GET /products/refs?ids=1,2,3 (tenant-scoped). */
    @GetMapping("/refs")
    public java.util.List<com.myplus.commerce.contracts.dto.ProductRef> getRefs(
            @org.springframework.web.bind.annotation.RequestParam java.util.List<Long> ids) {
        return productService.getRefs(ids);
    }

    /**
     * B1: set the pharmacy clinical flags on a product. Catalog is the single writer (see
     * docs/pharmacy-rx-enforcement-design.md D2); the pharmacy Clinical &amp; Safety screen calls through here.
     * ADMIN-gated like the rest of the clinical surface — clearing {@code controlledSubstance} drops later
     * dispenses off the regulatory register, and setting {@code rxRequired} governs whether the tills refuse a sale.
     */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @PutMapping("/{id}/clinical-flags")
    public com.myplus.commerce.contracts.dto.ProductRef updateClinicalFlags(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean rxRequired,
            @RequestParam(required = false) Boolean controlledSubstance) {
        return productService.updateClinicalFlags(id, rxRequired, controlledSubstance);
    }

    /** M4e.c (slice 103): tenant-scoped product count for the dashboard KPI — GET /products/count. */
    @GetMapping("/count")
    public long count() {
        return productService.count();
    }

    /**
     * "Is this name already registered?" — the product form asks on focus-out of the Name field.
     * GET /products/name-check?name=X&excludeId=12. Advisory: it reports the namesake so the operator can edit
     * it instead of creating a twin; creating a duplicate name is still allowed (only a duplicate SKU is refused).
     * A literal path, so it never collides with GET /products/{id}.
     */
    @GetMapping("/name-check")
    public ResponseEntity<ApiResponse<NameCheckDTO>> nameCheck(
            @RequestParam String name,
            @RequestParam(required = false) Long excludeId) {
        return ResponseEntity.ok(ApiResponse.success(productService.checkName(name, excludeId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTO>> update(@PathVariable Long id, @RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.update(id, dto), "Updated"));
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
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

    /** Re-price on receive (Option B): the purchase/goods-in flow sets the selling price and stamps the rates this
     *  purchase carried (sold-at and bought-at) onto the master. {@code purchaseRate} is optional so pre-existing
     *  callers that only re-price keep working unchanged. */
    @PutMapping("/{id}/price")
    public ResponseEntity<ApiResponse<ProductDTO>> updatePrice(@PathVariable Long id,
                                                               @RequestParam(required = false) BigDecimal price,
                                                               @RequestParam(required = false) BigDecimal purchaseRate) {
        return ResponseEntity.ok(ApiResponse.success(productService.updatePrice(id, price, purchaseRate), "Price updated"));
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
