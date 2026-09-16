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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProductController.class);

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
    /**
     * DUP-1 — create, or replay the create this form-fill already performed.
     *
     * <p><b>Why the catch is HERE and not in the service.</b> {@code ProductService.create} is
     * {@code @Transactional}. When the V16 unique index rejects a concurrent twin, that transaction is marked
     * rollback-only, so reading the winner's row from inside it is impossible — the read would fail too. This
     * method runs OUTSIDE the transaction: by the time the exception arrives here the failed insert has rolled
     * back cleanly and a fresh read is free to succeed. (The sale reaches the same place differently: an
     * untransactional orchestrator over a REQUIRES_NEW writer bean. Products need no extra bean for it.)
     *
     * <p>⚠ AND THIS IS THE PATH THAT FIXES THE REPORTED DEFECT, not the service's pre-check. A shop registered
     * 148 products from a single submit with a held Enter; all of those requests overlapped, so every pre-check
     * found nothing. Only the index can separate concurrent twins, which makes this handler — the loser's
     * replay — the one carrying the load. Deleting it would leave a guard that passes a sequential test and
     * still lets the real burst through.
     *
     * <p>The replay answers exactly as the winner did: same product, same "Created". A caller cannot tell which
     * of its repeated submits won, and has no reason to care.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ProductDTO>> create(@Valid @RequestBody ProductDTO dto) {
        try {
            return ResponseEntity.ok(ApiResponse.success(productService.create(dto), "Created"));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Only a duplicate KEY is replayable. Any other constraint (a real data fault) must surface as
            // itself rather than be reported as a successful save — so if no row carries this key, rethrow.
            return productService.findByIdempotencyKey(dto.getIdempotencyKey())
                    .map(existing -> {
                        LOG.info("create: idempotent race for key {} -> returning the winner's product {}",
                                dto.getIdempotencyKey(), existing.getId());
                        return ResponseEntity.ok(ApiResponse.success(existing, "Created"));
                    })
                    .orElseThrow(() -> dup);
        }
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

    /**
     * U12 — every sticker in the shop, for the label sheet.
     *
     * <p>U7 exposed stickers one product at a time, which is right for the product form and wrong for a
     * screen that prints a shelf's worth: a 1,200-product catalogue would have meant 1,200 requests to find
     * the handful of products that actually carry a sticker.
     */
    @GetMapping("/barcodes")
    public ResponseEntity<ApiResponse<java.util.List<com.myplus.catalog.entity.ProductBarcode>>> allBarcodes() {
        return ResponseEntity.ok(ApiResponse.success(productBarcodeService.forOrg()));
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

    /**
     * M4d (slice 93): batch refs by id for the POS read screens — GET /products/refs?ids=1,2,3 (tenant-scoped).
     *
     * <p>CACHE-3: cache-aside per product, evicted after every committed product, category or tax-code write.
     * {@code fresh=true} bypasses it and reads MySQL — for the callers that decide MONEY or SAFETY from the answer
     * (the sell saga prices a line from {@code sellingPrice} and refuses a prescription-only medicine on
     * {@code rxRequired}). Defaults false, so a read screen gets the cache without asking.
     */
    @GetMapping("/refs")
    public java.util.List<com.myplus.commerce.contracts.dto.ProductRef> getRefs(
            @org.springframework.web.bind.annotation.RequestParam java.util.List<Long> ids,
            @org.springframework.web.bind.annotation.RequestParam(name = "fresh", required = false,
                    defaultValue = "false") boolean fresh) {
        return productService.getRefs(ids, fresh);
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

    /**
     * "Is this SKU already taken?" — GET /products/sku-check?sku=X&excludeId=12 (PS-1b).
     *
     * <p>The companion to {@code /name-check}, and the more consequential of the two: a duplicate name is
     * allowed, a duplicate SKU is refused. A literal path, so it never collides with GET /products/{id}.
     */
    @GetMapping("/sku-check")
    public ResponseEntity<ApiResponse<NameCheckDTO>> skuCheck(
            @RequestParam String sku,
            @RequestParam(required = false) Long excludeId) {
        return ResponseEntity.ok(ApiResponse.success(productService.checkSku(sku, excludeId)));
    }

    /**
     * The tenant's distinct manufacturers — GET /products/manufacturers (PS-1b).
     *
     * <p>Small, changes only on a product write, and identical for everyone in the tenant, so it is a
     * prime candidate for a conditional GET; the BFF carries the ETag.
     */
    @GetMapping("/manufacturers")
    public ResponseEntity<ApiResponse<java.util.List<String>>> manufacturers() {
        return ResponseEntity.ok(ApiResponse.success(productService.manufacturers()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDTO>> update(@PathVariable Long id, @RequestBody ProductDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(productService.update(id, dto), "Updated"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.catalog.service.ProductDeletionService productDeletionService;

    /**
     * PROD-DEL — permanently delete a DEACTIVATED product. The shop OWNER only (the user's ruling), and refused while
     * anything still references it; ProductDeletionService says what. A repeat answers "Already removed".
     *
     * <p>Was {@code DELETE_PRIVILEGE} with a plain row delete that orphaned every reference; it had no caller.
     */
    @PreAuthorize("hasAuthority('ROLE_OWNER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        var outcome = productDeletionService.deletePermanently(id);
        return ResponseEntity.ok(ApiResponse.success(null,
                outcome == com.myplus.catalog.service.ProductDeletionService.Outcome.DELETED ? "Deleted" : "Already removed"));
    }

    /**
     * Products per category \u2014 the dashboard card, and the counts its drill-through must match.
     *
     * <p>Sits beside {@code /count} rather than inside it: the KPI is one number and this is a breakdown, and
     * a caller that wants one should not pay for the other.
     */
    @GetMapping("/category-counts")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> categoryCounts() {
        return ResponseEntity.ok(ApiResponse.success(productService.categoryCounts()));
    }

    /**
     * Paged product search.
     *
     * <p>{@code uncategorised=true} selects the products with NO category \u2014 a distinct request from
     * omitting {@code category}, which means "any". Both new flags default to false, so a caller that passes
     * neither gets an active-only, unfiltered page.
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<ProductDTO>>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false, defaultValue = "false") boolean uncategorised,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                productService.search(q, category, uncategorised, includeInactive, minPrice, maxPrice, pageable),
                p -> p)));
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
