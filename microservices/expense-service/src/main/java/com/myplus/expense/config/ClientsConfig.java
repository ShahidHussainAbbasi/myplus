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

    /**
     * EX-2b — one expense-tag proxy per module that owns taggable things (the SPI in commerce-contracts). Read when
     * the form opens and once per save; identity is forwarded, so each module applies the caller's own scoping.
     */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("educationTags")
    public com.myplus.commerce.contracts.client.ExpenseTagClient educationTags(@LoadBalanced RestClient.Builder builder) {
        return tagClient(builder, "http://education-service");
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("agricultureTags")
    public com.myplus.commerce.contracts.client.ExpenseTagClient agricultureTags(@LoadBalanced RestClient.Builder builder) {
        return tagClient(builder, "http://agriculture-service");
    }

    /** FP-3 — business-service answers the same SPI with the caller's suppliers (who a bill is owed to). */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("businessTags")
    public com.myplus.commerce.contracts.client.ExpenseTagClient businessTags(@LoadBalanced RestClient.Builder builder) {
        return tagClient(builder, "http://business-service");
    }

    /**
     * EX-7b — auth-service, for "who is staff here". auth's member list scopes by the caller's own JWT (its active org
     * and its owner/admin role), so the caller's Authorization header is passed on as it came, alongside the identity
     * headers. No token, no answer: a request with no caller cannot list anyone.
     */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("authRestClient")
    public RestClient authRestClient(@LoadBalanced RestClient.Builder builder) {
        return builder.clone()
                .baseUrl("http://auth-service")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .requestInterceptor((request, body, execution) -> {
                    var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                    if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                        String auth = sra.getRequest().getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
                        if (auth != null && !request.getHeaders().containsKey(org.springframework.http.HttpHeaders.AUTHORIZATION))
                            request.getHeaders().add(org.springframework.http.HttpHeaders.AUTHORIZATION, auth);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    private static com.myplus.commerce.contracts.client.ExpenseTagClient tagClient(RestClient.Builder builder, String base) {
        RestClient rc = builder.clone()
                .baseUrl(base)
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build()
                .createClient(com.myplus.commerce.contracts.client.ExpenseTagClient.class);
    }

    /** EX-9a — business-service's till pay-outs that never reached the books (read as the caller; internal route). */
    @Bean
    public com.myplus.commerce.contracts.client.DrawerHistoryClient drawerHistoryClient(@LoadBalanced RestClient.Builder builder) {
        RestClient rc = builder.clone()
                .baseUrl("http://business-service")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build()
                .createClient(com.myplus.commerce.contracts.client.DrawerHistoryClient.class);
    }

    /** EX-9b — agriculture-service's old farm expense rows not yet in the books (read as the caller; internal route). */
    @Bean
    public com.myplus.commerce.contracts.client.FarmHistoryClient farmHistoryClient(@LoadBalanced RestClient.Builder builder) {
        RestClient rc = builder.clone()
                .baseUrl("http://agriculture-service")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build()
                .createClient(com.myplus.commerce.contracts.client.FarmHistoryClient.class);
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
