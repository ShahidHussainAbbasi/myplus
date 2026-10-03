package com.myplus.market.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * MP-0b talks to one service: audit-service, through the shared contract, after commit (outbox). The stock
 * authority, catalog and finance clients join in MP-2/MP-4/MP-7, each with its own timeouts (STANDARDS D3e).
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
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        RestClient rc = builder.clone()
                .baseUrl("http://audit-service/api/audit")
                .requestFactory(timeouts())
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(AuditClient.class);
    }
}
