package com.myplus.catalog.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.common.security.CurrentUser;
import com.myplus.catalog.dto.NameCheckDTO;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.dto.ProductPickerDTO;
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
    private final com.myplus.catalog.service.ProductBarcodeService productBarcodeService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductDTO>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(productService.getAll(pageable), p -> p)));
    }

    /**
     * PERF-8 — the read behind every product {@code <select>} on the platform.
     *
     * <p>Separate from {@link #getAll} because the two have genuinely different jobs: that one backs a product
     * LIST, which needs every column and shows deactivated rows; this one backs a PICKER, which needs three
     * columns and must never offer something unsellable.
     *
     * <p>Measured before it was written: the picker was being served from {@code getAll}, so 83% of every
     * product's payload was transferred and discarded, and 3 requests were issued per section open for a
     * 1 249-product tenant. See {@code docs/slices/perf-8-product-picker.md}.
     *
     * <p><b>Still paged, and that is deliberate.</b> Returning a plain list would be the unbounded read OMS-7
     * named, and {@code paged-fetch.js} exists because a fixed {@code ?size=2000} once truncated a large
     * tenant's catalogue in silence. The envelope is kept so the client's existing paging transport still
     * handles the over-cap case correctly — it is simply that a lean row makes ONE page enough for any
     * realistic tenant, which is what removes the multi-wave request pattern.
     */
    @GetMapping("/picker")
    public ResponseEntity<ApiResponse<PageResponse<ProductPickerDTO>>> picker(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(productService.getPicker(pageable), p -> p)));
    }

    // Slice 106: @Valid enforces ProductDTO's constraints. Without it the annotations are inert decoration —
    // the DTO carried none and this carried no @Valid, so a nameless product saved happily.
    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTO>> create(@Valid @RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.create(dto), "Created"));
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
    /**
     * U7 — resolve a scanned code to <b>this many of this product, in this unit</b>.
     *
     * <p>Separate from {@link #lookup} deliberately: {@code /lookup} answers "which product" and is read by
     * callers that have no notion of a quantity. Changing its answer would change theirs. This endpoint is
     * the scan path's own question, and the scan path is its only caller.
     *
     * <p>A manufacturer barcode resolves here to {@code PACK × 1} — the answer the till has always acted on,
     * now stated rather than assumed.
     */
    @GetMapping("/scan")
    public com.myplus.commerce.contracts.dto.ScanResolution scan(@RequestParam("code") String code) {
        return productBarcodeService.scan(code);
    }

    /** U7 — the shop's own stickers on one product, for the product form. */
    @GetMapping("/{id}/barcodes")
    public ResponseEntity<ApiResponse<java.util.List<com.myplus.catalog.entity.ProductBarcode>>> barcodes(
            @PathVariable Long id) {
        /*
         * ⚠ WRAPPED, and it must be. A bare JSON ARRAY cannot be deserialised into the Map<String,Object>
         * the monolith's catalog client reads, so returning the list raw made every read throw and the proxy
         * answer {success:false} with no rows — a list that was never empty looking empty.
         *
         * Every other endpoint on this controller answers in ApiResponse; this one was the outlier, which is
         * the whole argument for having a house envelope (governing standard 8).
         */
        return ResponseEntity.ok(ApiResponse.success(productBarcodeService.forProduct(id)));
    }

    /** U7 — register a sticker. Refuses a code that would shadow a real product barcode; see the service. */
    @PostMapping("/{id}/barcodes")
    public com.myplus.catalog.entity.ProductBarcode addBarcode(@PathVariable Long id,
                                                               @RequestBody java.util.Map<String, Object> body) {
        Object qty = body.get("quantity");
        return productBarcodeService.register(id,
                body.get("barcode") == null ? null : String.valueOf(body.get("barcode")),
                body.get("soldUnit") == null ? null : String.valueOf(body.get("soldUnit")),
                qty == null ? null : Integer.valueOf(String.valueOf(qty).trim()));
    }

    /** U7 — remove a sticker. Ordinary lookup for that code resumes immediately. */
    @DeleteMapping("/barcodes/{barcodeId}")
    public void removeBarcode(@PathVariable Long barcodeId) {
        productBarcodeService.remove(barcodeId);
    }

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

    /**
     * C6: set the per-product TRACKING policy — serial/IMEI and batch.
     *
     * <p>Sibling of {@code /clinical-flags} and ADMIN-gated for the same reason: turning {@code requiresSerial}
     * on governs whether the tills demand an IMEI before a handset can be sold, which is not a setting for
     * whoever happens to be at the counter.
     *
     * <p>Two gates, two questions. {@code @PreAuthorize} asks whether this USER may write; the service asks
     * whether this TENANT has the capability at all. A mobile shop's admin has every write privilege and still
     * cannot mark a product batch-tracked if the business does not do batch trade.
     */
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @PutMapping("/{id}/tracking-flags")
    public com.myplus.commerce.contracts.dto.ProductRef updateTrackingFlags(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean requiresSerial,
            @RequestParam(required = false) Boolean tracksBatch) {
        return productService.updateTrackingFlags(id, requiresSerial, tracksBatch);
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
