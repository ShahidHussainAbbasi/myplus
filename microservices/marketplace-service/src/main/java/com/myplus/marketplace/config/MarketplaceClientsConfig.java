package com.myplus.marketplace.config;

import com.myplus.commerce.contracts.client.CatalogClient;
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

    /**
     * OMS O1 — the seam that puts a storefront order into the books. business-service is the sole author of
     * trade sales, so marketplace hands over what was bought and paid and lets the existing sale path invoice
     * it. Same load-balanced, identity-forwarding, internal-secret-stamped recipe as the others.
     *
     * <p><b>No short timeout here, unlike {@link #partyClient}.</b> The party bridge is best-effort — a slow
     * party-service should fail fast and re-link later. This call is NOT best-effort: it is the only thing that
     * creates the invoice, and giving up early would leave stock reserved and the shopper charged with no sale
     * in the books. It must be allowed to finish.
     */
    @Bean
    public com.myplus.commerce.contracts.client.TradeClient tradeClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://business-service")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .requestInterceptor(internalSecretInterceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(com.myplus.commerce.contracts.client.TradeClient.class);
    }

    /** Catalog lookup for authoritative cart line pricing (slice 68) — same load-balanced, identity-forwarding,
     *  internal-secret-stamped recipe as {@link #inventoryClient}. */
    @Bean
    public CatalogClient catalogClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://catalog-service/api/catalog")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .requestInterceptor(internalSecretInterceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(CatalogClient.class);
    }

    /** P3 party bridge: the shared contact master. Same identity-forwarding + internal-secret recipe (the storefront
     *  register is anonymous, so the bridge stamps the org via runAs → the interceptor forwards it). Short timeout so
     *  a slow party-service fails fast to best-effort. */
    @Bean
    public com.myplus.commerce.contracts.client.PartyClient partyClient(@LoadBalanced RestClient.Builder builder) {
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1000);
        rf.setReadTimeout(2000);
        RestClient restClient = builder.clone()
                .baseUrl("http://party-service/api/party/parties")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .requestInterceptor(internalSecretInterceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(com.myplus.commerce.contracts.client.PartyClient.class);
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
