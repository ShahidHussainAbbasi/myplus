package com.myplus.auth.config;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.security.GatewayIdentityForwarding;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * E4 — the load-balanced {@link AuditClient} proxy auth-service delivers control-plane events through.
 *
 * <h3>⚠ The interceptor is not optional here, and its absence would be silent</h3>
 * {@link GatewayIdentityForwarding#interceptor()} is what turns the producer's
 * {@code runAs(actorUserId, subjectOrgId)} into the {@code X-User-Id} / {@code X-Org-Id} headers audit-service
 * authenticates and scopes from — plus {@code X-Internal-Secret}, which a background delivery has no inbound
 * request to copy. Without it the POST arrives anonymous: audit-service resolves no organization and files
 * every control-plane event against a null tenant. Nothing throws, the operator sees success, and the trail
 * is quietly empty for every customer.
 *
 * <p>{@code NotificationClientConfig} deliberately does not add it — an email is sent to an address, not on
 * behalf of a tenant — which is why this is a separate configuration rather than another bean there.
 *
 * <p>Base URL includes audit-service's controller prefix: its controllers map at the full
 * {@code /api/audit/...} path (the gateway routes to it with no StripPrefix), so the contract's relative
 * {@code /record} resolves correctly.
 */
@Configuration
public class AuditClientConfig {

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://audit-service/api/audit")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AuditClient.class);
    }
}
