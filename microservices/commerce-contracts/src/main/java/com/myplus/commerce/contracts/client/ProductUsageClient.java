package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.ProductUsage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * PROD-DEL — "does anything of yours still reference this product?"
 *
 * <p>ONE contract, implemented by every service that stores a product id: business, inventory, marketplace and
 * pharma. catalog builds one proxy per service, each with that service's bare base URL
 * ({@code http://business-service}, …), and asks all of them before it deletes a product permanently.
 *
 * <p>⚠ {@code /internal/**}: no gateway route matches it, so only an in-network caller reaches it (the mechanism
 * BLK-0 relies on). The identity is forwarded by {@code GatewayIdentityForwarding.interceptor()}, and a request with
 * no tenant is refused. Design: docs/slices/prod-del-product-permanent-delete.md §3.
 */
@HttpExchange(accept = "application/json")
public interface ProductUsageClient {

    @GetExchange("/internal/product-usage/{productId}")
    ProductUsage usage(@PathVariable("productId") Long productId);
}
