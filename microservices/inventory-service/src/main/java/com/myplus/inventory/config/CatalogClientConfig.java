package com.myplus.inventory.config;

import com.myplus.commerce.contracts.client.CatalogClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.List;

/**
 * Builds the {@link CatalogClient} proxy (slice 33, Phase 5c): a declarative @HttpExchange client backed by
 * a load-balanced RestClient pointed at {@code lb://catalog-service}. Inter-service calls bypass the gateway,
 * so we re-propagate the gateway's identity headers onto the outbound request — otherwise catalog-service's
 * HeaderAuthFilter sees no caller and the call is unscoped/anonymous.
 */
@Configuration
public class CatalogClientConfig {

    /** Identity headers stamped by the gateway that catalog-service needs to authenticate + scope the call. */
    private static final List<String> FORWARDED = List.of(
            "X-User-Id", "X-User-Email", "X-User-Roles", "X-User-Privileges", "X-Org-Id", "X-Internal-Secret");

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
                .requestInterceptor(identityPropagationInterceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(CatalogClient.class);
    }

    private ClientHttpRequestInterceptor identityPropagationInterceptor() {
        return (request, body, execution) -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest inbound = attrs.getRequest();
                for (String h : FORWARDED) {
                    String v = inbound.getHeader(h);
                    if (v != null && !request.getHeaders().containsKey(h)) {
                        request.getHeaders().add(h, v);
                    }
                }
            }
            return execution.execute(request, body);
        };
    }
}
