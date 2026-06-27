package com.myplus.pharma.config;

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
 * Declarative HTTP clients pharma-service uses to COMPOSE the shared commerce core (P0a, slice 41): {@link CatalogClient}
 * for the product master/price and {@link InventoryClient} for stock (FEFO/expiry, reservations) — so a medicine is a
 * catalog Product (+ clinical profile) and pharmacy stock is inventory StockEntry, never re-stored here. Both are
 * load-balanced @HttpExchange proxies that re-propagate the gateway identity via {@link GatewayIdentityForwarding}.
 */
@Configuration
public class PharmaClientsConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public CatalogClient catalogClient(@LoadBalanced RestClient.Builder builder) {
        return proxy(builder, "http://catalog-service/api/catalog", CatalogClient.class);
    }

    @Bean
    public InventoryClient inventoryClient(@LoadBalanced RestClient.Builder builder) {
        return proxy(builder, "http://inventory-service/api/inventory", InventoryClient.class);
    }

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
