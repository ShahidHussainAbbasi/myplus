package com.myplus.marketplace.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * MKT-1g — marketplace-service's client for finance-service, the only journal writer (ruling R-MKT-3). Called only
 * by the GL outbox after commit, never on a request path. The identity interceptor carries the operator's books org,
 * so finance posts into the right ledger; the base URL is the bare service because {@link FinanceClient}'s paths are
 * absolute.
 */
@Configuration
public class MarketplaceFinanceConfig {

    @Bean
    public FinanceClient financeClient(@LoadBalanced RestClient.Builder builder) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        RestClient rc = builder.clone()
                .baseUrl("http://finance-service")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(FinanceClient.class);
    }
}
