package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.ProductRef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative client for catalog-service product lookups (slice 33). Lets trade/pharma resolve a
 * {@link ProductRef} without owning the catalog entity. Proxy (base {@code lb://catalog-service}) is wired
 * in the consuming service once catalog-service exists (Phase 5/6); this is the contract only.
 */
@HttpExchange(accept = "application/json")
public interface CatalogClient {

    /** Resolve a single product reference by its catalog id (tenant-scoped via propagated headers). */
    @GetExchange("/products/{id}")
    ProductRef getProduct(@PathVariable Long id);
}
