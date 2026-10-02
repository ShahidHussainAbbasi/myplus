package com.myplus.expense.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * The two services expense-service talks to, both through the shared contracts (anti-corruption layer):
 * finance-service (the ledger) and audit-service (the trail). Each has its own timeouts (STANDARDS D3e) and
 * forwards the caller's identity, so finance scopes the posting to the right tenant.
 *
 * <p>Neither is called on a user's request path for a write: postings and audit records leave through outboxes
 * after commit. The one synchronous call is reading the chart of accounts when an owner maps a category.
 */
@Configuration
public class ClientsConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        return rf;
    }

    @Bean
    public FinanceClient financeClient(@LoadBalanced RestClient.Builder builder) {
        RestClient rc = builder.clone()
                .baseUrl("http://finance-service")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(FinanceClient.class);
    }

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        RestClient rc = builder.clone()
                .baseUrl("http://audit-service/api/audit")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(AuditClient.class);
    }
}
