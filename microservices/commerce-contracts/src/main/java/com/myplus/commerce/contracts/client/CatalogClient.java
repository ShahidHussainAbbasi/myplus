package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.commerce.contracts.dto.ProductRef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Declarative client for catalog-service product lookups (slice 33). Lets trade/pharma resolve a
 * {@link ProductRef} without owning the catalog entity. Proxy (base {@code lb://catalog-service}) is wired
 * in the consuming service once catalog-service exists (Phase 5/6); this is the contract only.
 */
@HttpExchange(accept = "application/json")
public interface CatalogClient {

    /** Resolve a product reference (+ price) by its catalog id — raw ProductRef, tenant-scoped via headers. */
    @GetExchange("/products/{id}/ref")
    ProductRef getProduct(@PathVariable Long id);

    /** M4d (slice 93): batch-resolve product references by id (for list/read screens — one call instead of N).
     *  Tenant-scoped via headers; missing/foreign ids are simply omitted from the result. */
    @GetExchange("/products/refs")
    List<ProductRef> getProducts(@RequestParam("ids") List<Long> ids);

    /** Bulk import products (item→product migration, slice 33 U2). Returns the clientRef→productId map. */
    @PostExchange("/products/import")
    List<ProductImportResult> importProducts(@RequestBody List<ProductImportLine> items);
}
