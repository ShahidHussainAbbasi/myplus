package com.myplus.marketplace.config;

import java.util.List;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.outbox.OutboxHealthRegistry;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * MKT-1f (G-16) — marketplace-service's client for the shared audit-service, and the outboxes it owns.
 *
 * <p>⚠ The identity interceptor is not optional: it turns the emitter's {@code runAs(user, subjectOrg)} into the
 * headers audit-service authenticates from, and adds the internal secret on a background re-drive. Without it every
 * POST arrives anonymous and is refused — the action succeeds and the record silently never exists (E4, E5).
 *
 * <p>The registry switches the shared {@code /outbox-health} endpoint on for this service and is its re-drive
 * allow-list. marketplace-service owned no outbox before V30.
 */
@Configuration
public class MarketplaceAuditConfig {

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://audit-service/api/audit")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(AuditClient.class);
    }

    @Bean
    public OutboxHealthRegistry outboxHealthRegistry() {
        return () -> List.of("audit_outbox");
    }
}
