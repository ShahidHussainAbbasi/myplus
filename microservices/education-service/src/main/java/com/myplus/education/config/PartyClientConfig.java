package com.myplus.education.config;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Party bridge (P3): the {@link PartyClient} proxy for education-service — load-balanced @HttpExchange at
 * {@code lb://party-service/api/party/parties}. Re-propagates the gateway identity (X-Org-Id / X-User-*) via
 * {@link GatewayIdentityForwarding#interceptor()} so party-service scopes the upsert to the caller's org (else the
 * party would be created org-less). Reuses the @LoadBalanced RestClient.Builder already defined for notifications.
 */
@Configuration
public class PartyClientConfig {

    @Bean
    public PartyClient partyClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder.clone()
                .baseUrl("http://party-service/api/party/parties")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(PartyClient.class);
    }
}
