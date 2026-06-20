package com.myplus.business_service.config;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Declarative HTTP clients trade-service (currently business-service) uses for the sell↔stock saga
 * (slice 33, Phase 6b): {@link InventoryClient} for reserve/confirm/release and {@link CatalogClient} for
 * the catalog list price (D1). Both are load-balanced @HttpExchange proxies that re-propagate the gateway
 * identity via the shared {@link GatewayIdentityForwarding} interceptor. Base URLs include each callee's
 * controller prefix so the contract's relative paths resolve correctly.
 */
@Configuration
public class TradeClientsConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public InventoryClient inventoryClient(@LoadBalanced RestClient.Builder builder) {
        return proxy(builder, "http://inventory-service/api/inventory", InventoryClient.class);
    }

    @Bean
    public CatalogClient catalogClient(@LoadBalanced RestClient.Builder builder) {
        return proxy(builder, "http://catalog-service/api/catalog", CatalogClient.class);
    }

    /** Build a declarative client over a cloned, load-balanced RestClient (clone isolates per-client config). */
    private <T> T proxy(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(type);
    }
}
