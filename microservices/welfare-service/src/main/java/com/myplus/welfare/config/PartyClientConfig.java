package com.myplus.welfare.config;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Party bridge (P3): the {@link PartyClient} proxy for welfare-service — load-balanced @HttpExchange at
 * {@code lb://party-service/api/party/parties}. Re-propagates the gateway identity via
 * {@link GatewayIdentityForwarding#interceptor()} so party-service scopes the upsert to the caller's org.
 */
@Configuration
public class PartyClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public PartyClient partyClient(@LoadBalanced RestClient.Builder builder) {
        // Hardening: bound the party call with a short timeout so a SLOW party-service fails fast to best-effort.
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1000);
        rf.setReadTimeout(2000);
        RestClient restClient = builder.clone()
                .baseUrl("http://party-service/api/party/parties")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PartyClient.class);
    }
}
