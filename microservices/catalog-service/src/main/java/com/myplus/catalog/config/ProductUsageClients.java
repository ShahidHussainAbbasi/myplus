package com.myplus.catalog.config;

import com.myplus.commerce.contracts.client.ProductUsageClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROD-DEL — who catalog must ask before it deletes a product permanently, and a client for each.
 *
 * <h3>Required and optional, because deployments differ</h3>
 * business and inventory run in every compose profile and hold most references (sales, purchases, stock), so they
 * are REQUIRED: no answer from either and the delete is refused. marketplace runs only in {@code full}, pharma only
 * in {@code full}/{@code pharmacy}, so a POS-only deployment has neither service nor database. They are consulted
 * when registered in discovery. Both lists are configuration, so a deployment states which rule applies to it.
 *
 * <p>Short timeouts: this sits in front of an owner waiting on a Delete button, and a hung service must fail fast
 * to a refusal rather than hold the request. The identity interceptor is not optional: without it the internal
 * endpoint refuses an anonymous caller, which would read as "could not confirm" on every delete.
 */
@Component
public class ProductUsageClients {

    private final RestClient.Builder builder;
    private final DiscoveryClient discovery;
    private final List<String> required;
    private final List<String> optional;
    private final Map<String, ProductUsageClient> clients = new ConcurrentHashMap<>();

    public ProductUsageClients(@LoadBalanced RestClient.Builder builder, DiscoveryClient discovery,
                               @Value("${catalog.product-usage.required:business-service,inventory-service}") String required,
                               @Value("${catalog.product-usage.optional:marketplace-service,pharma-service}") String optional) {
        this.builder = builder;
        this.discovery = discovery;
        this.required = split(required);
        this.optional = split(optional);
    }

    public List<String> required() { return required; }

    public List<String> optional() { return optional; }

    /** Is at least one instance of this service registered right now? */
    public boolean registered(String serviceId) {
        return !discovery.getInstances(serviceId).isEmpty();
    }

    public ProductUsageClient clientFor(String serviceId) {
        return clients.computeIfAbsent(serviceId, id -> {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(2000);
            rf.setReadTimeout(5000);
            RestClient restClient = builder.clone()
                    .baseUrl("http://" + id)
                    .requestFactory(rf)
                    .requestInterceptor(GatewayIdentityForwarding.interceptor())
                    .build();
            return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                    .createClient(ProductUsageClient.class);
        });
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
