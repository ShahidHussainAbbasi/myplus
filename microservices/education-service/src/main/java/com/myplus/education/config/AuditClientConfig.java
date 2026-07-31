package com.myplus.education.config;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Slice 1.3 (D5): the {@link AuditClient} proxy for education-service — load-balanced @HttpExchange at
 * {@code lb://audit-service/api/audit}, so every marks entry and every exam lock/unlock is recorded on
 * the shared, immutable audit trail rather than in a service-local table.
 *
 * Same base URL business-service uses; the contract lives in commerce-contracts so neither service
 * depends on the other's internals (DIP).
 *
 * Identity is re-propagated by {@link GatewayIdentityForwarding#interceptor()} for request-thread
 * deliveries; {@code EduAuditService} additionally wraps scheduled relay deliveries in {@code runAs},
 * because a background thread has no inbound request to forward.
 *
 * Timeouts match FinanceClientConfig rather than the party bridge's 1s/2s: this call sits behind an
 * outbox, so a slow audit-service costs a retry rather than a lost event — failing fast buys nothing.
 */
@Configuration
public class AuditClientConfig {

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        RestClient restClient = builder.clone()
                .baseUrl("http://audit-service/api/audit")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AuditClient.class);
    }
}
