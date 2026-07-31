package com.myplus.education.config;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Slice 0.1: the {@link FinanceClient} proxy for education-service — load-balanced @HttpExchange at
 * {@code lb://finance-service/api/finance}, so a fee collection can post its journal entry to the shared GL.
 *
 * Identity is re-propagated by {@link GatewayIdentityForwarding#interceptor()} for request-thread deliveries;
 * the outbox relay additionally wraps scheduled deliveries in {@code runAs}, because a background thread has no
 * inbound request to forward.
 *
 * Timeouts are deliberately generous compared with the party bridge's 1s/2s: this call is behind an outbox, so a
 * slow finance-service costs a retry rather than a lost posting — failing fast buys nothing here.
 */
@Configuration
public class FinanceClientConfig {

    @Bean
    public FinanceClient financeClient(@LoadBalanced RestClient.Builder builder) {
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        RestClient restClient = builder.clone()
                .baseUrl("http://finance-service/api/finance")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(FinanceClient.class);
    }
}
