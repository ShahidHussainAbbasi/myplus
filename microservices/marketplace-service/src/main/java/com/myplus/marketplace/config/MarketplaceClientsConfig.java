package com.myplus.marketplace.config;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Declarative HTTP client marketplace-service uses to drive the inventory reservation saga for storefront
 * orders (slice 49) — the SAME {@link InventoryClient} reserve/confirm/release that POS uses. A load-balanced
 * @HttpExchange proxy that re-propagates the gateway identity via {@link GatewayIdentityForwarding} (so a
 * {@code runAs(user, org, …)} override carries X-User-Id/X-Org-Id to inventory) and stamps the internal secret
 * so the call is trusted in prod (where inventory's HeaderAuthFilter enforces it). Mirrors business-service's
 * TradeClientsConfig.
 */
@Configuration
public class MarketplaceClientsConfig {

    /** Must match the gateway/services' internal secret; empty in dev = not enforced. */
    @Value("${service.internal-secret:}")
    private String internalSecret;

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public InventoryClient inventoryClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://inventory-service/api/inventory")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .requestInterceptor(internalSecretInterceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(InventoryClient.class);
    }

    /** Stamp X-Internal-Secret on the outbound call (no inbound request to forward it from, since the storefront
     *  order is anonymous). No-op when the secret is unset (dev). */
    private ClientHttpRequestInterceptor internalSecretInterceptor() {
        return (request, body, execution) -> {
            if (internalSecret != null && !internalSecret.isEmpty()
                    && !request.getHeaders().containsKey("X-Internal-Secret")) {
                request.getHeaders().add("X-Internal-Secret", internalSecret);
            }
            return execution.execute(request, body);
        };
    }
}
