package com.myplus.catalog.config;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.security.GatewayIdentityForwarding;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * E5 — catalog-service's client for the shared audit-service.
 *
 * <h3>⚠ The interceptor is not optional, and its absence is silent</h3>
 * {@link GatewayIdentityForwarding#interceptor()} turns the producer's {@code runAs(user, subjectOrg)} into
 * the {@code X-User-Id} / {@code X-Org-Id} headers audit-service authenticates and scopes from — and, on a
 * background delivery with no inbound request to copy from, the {@code X-Internal-Secret} that audit-service
 * enforces. Without it every POST arrives anonymous and is refused with a bare 403: the write succeeds, the
 * operator sees nothing wrong, and the record simply never exists. E4 lost three gate runs to exactly that,
 * in auth-service, for exactly this reason.
 *
 * <p>catalog-service called no peer before this slice, so the load-balanced builder is declared here too.
 */
@Configuration
public class AuditClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                // audit-service maps its controllers at the full /api/audit path (the gateway routes to it
                // with no StripPrefix), so the base URL carries the prefix and the contract's relative
                // /record resolves correctly.
                .baseUrl("http://audit-service/api/audit")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AuditClient.class);
    }
}
