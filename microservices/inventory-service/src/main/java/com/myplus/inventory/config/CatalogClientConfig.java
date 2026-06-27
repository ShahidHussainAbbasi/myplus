package com.myplus.inventory.config;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the {@link CatalogClient} proxy (slice 33, Phase 5c): a declarative @HttpExchange client backed by
 * a load-balanced RestClient pointed at {@code lb://catalog-service}, with the shared
 * {@link GatewayIdentityForwarding} interceptor so catalog-service authenticates + scopes the call.
 */
@Configuration
public class CatalogClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public CatalogClient catalogClient(@LoadBalanced RestClient.Builder builder) {
        // base includes catalog's controller prefix; getProduct("/products/{id}") -> /api/catalog/products/{id}.
        RestClient restClient = builder
                .baseUrl("http://catalog-service/api/catalog")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(CatalogClient.class);
    }
}
