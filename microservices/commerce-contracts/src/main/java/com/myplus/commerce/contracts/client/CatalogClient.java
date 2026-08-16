package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.commerce.contracts.dto.PriceQuote;
import com.myplus.commerce.contracts.dto.ProductRef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.math.BigDecimal;
import java.util.List;

/**
 * Declarative client for catalog-service product lookups (slice 33). Lets trade/pharma resolve a
 * {@link ProductRef} without owning the catalog entity. Proxy (base {@code lb://catalog-service}) is wired
 * in the consuming service once catalog-service exists (Phase 5/6); this is the contract only.
 */
@HttpExchange(accept = "application/json")
public interface CatalogClient {

    /**
     * B2B P2 (#10): what THIS buyer pays for THESE lines, resolved against the tenant's contract/tier rules.
     *
     * <p>Called ONCE per sale, never per line. Sends ids and quantities only — a caller never sends a price
     * and is never believed about one; catalog-service computes the answer from its own rules.
     *
     * <p>Callers must treat a failure here as "no rules": fall back to the catalog price and let the sale
     * proceed. A pricing outage must never stop a shop selling.
     */
    @PostExchange("/price-rules/quote")
    PriceQuote quote(@RequestBody PriceQuote request);

    /** Resolve a product reference (+ price) by its catalog id — raw ProductRef, tenant-scoped via headers. */
    @GetExchange("/products/{id}/ref")
    ProductRef getProduct(@PathVariable Long id);

    /** M4d (slice 93): batch-resolve product references by id (for list/read screens — one call instead of N).
     *  Tenant-scoped via headers; missing/foreign ids are simply omitted from the result. */
    @GetExchange("/products/refs")
    List<ProductRef> getProducts(@RequestParam("ids") List<Long> ids);

    /** M4e.c (slice 103): tenant-scoped product count (dashboard KPI). */
    @GetExchange("/products/count")
    long countProducts();

    /** Bulk import products (item→product migration, slice 33 U2). Returns the clientRef→productId map. */
    @PostExchange("/products/import")
    List<ProductImportResult> importProducts(@RequestBody List<ProductImportLine> items);

    /** Re-price on receive (Option B): set a product's selling price from the purchase/goods-in flow AND stamp the
     *  rates that purchase carried onto the master — what it was bought at and what it is to be sold at — so the
     *  Product screen reads them off the product row instead of deriving them from purchase history.
     *  Tenant-scoped via headers; guarded server-side (a null/≤0 rate never wipes the master). Either rate may be
     *  null to leave that field unchanged. */
    /**
     * NOTE {@code required = false} on both params — the same trap already documented on
     * {@code PartyClient.setAccountParent}. A Spring HTTP interface treats {@code @RequestParam} as REQUIRED by
     * default and throws CLIENT-SIDE, before any request is sent, when the argument is null. The server has
     * always declared both as optional; only this interface disagreed.
     *
     * <p>What that cost: {@code PurchaseService.stampRatesOnProduct} deliberately passes {@code null} for the
     * rate a bill does not carry, and wraps the call in a best-effort try/catch. So a purchase with a cost but
     * NO sell rate threw here, was swallowed as a warning, and the product's last purchase rate silently kept
     * its previous value — the bill saved, the stamp did not. The catalog-side unit test could not see it
     * because it calls the service directly and never crosses this interface.
     */
    @PutExchange("/products/{id}/price")
    void updatePrice(@PathVariable Long id,
                     @RequestParam(name = "price", required = false) BigDecimal price,
                     @RequestParam(name = "purchaseRate", required = false) BigDecimal purchaseRate);

    /** B1: set a product's pharmacy clinical flags. Catalog is the single writer for these — the pharmacy
     *  Clinical &amp; Safety screen goes through here. Either flag may be null to leave it unchanged. */
    @PutExchange("/products/{id}/clinical-flags")
    ProductRef updateClinicalFlags(@PathVariable Long id,
                                   @RequestParam(name = "rxRequired", required = false) Boolean rxRequired,
                                   @RequestParam(name = "controlledSubstance", required = false) Boolean controlledSubstance);
}
