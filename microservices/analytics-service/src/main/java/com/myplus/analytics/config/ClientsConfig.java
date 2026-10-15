package com.myplus.analytics.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * AN-1 — finance-service, the one service analytics reads its finance figures from. The caller's identity is forwarded,
 * so finance scopes the figures to the caller's tenant and applies its own statements rule to the caller.
 */
@Configuration
public class ClientsConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient financeRestClient(@LoadBalanced RestClient.Builder builder) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(8000);   // up to 24 months of P&L in one answer
        return builder.clone()
                .baseUrl("http://finance-service")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
    }
}
