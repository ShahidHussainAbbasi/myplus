package com.myplus.education.config;

import com.myplus.commerce.contracts.client.AuthAccountClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Slice 3.1b — the {@link AuthAccountClient} proxy, at {@code lb://auth-service/api/auth/portal}.
 *
 * <p>Same shape as {@link PartyClientConfig}, including the timeouts: a slow auth-service must fail fast
 * rather than pin the thread that is recording a school's decision to invite a guardian. The identity
 * interceptor stamps {@code X-Internal-Secret}, which is what {@code PortalAccountController} checks.
 */
@Configuration
public class AuthAccountClientConfig {

    @Bean
    public AuthAccountClient authAccountClient(@LoadBalanced RestClient.Builder builder) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1000);
        rf.setReadTimeout(3000);   // account creation writes + sends mail, so a little longer than party's
        RestClient restClient = builder.clone()
                .baseUrl("http://auth-service/api/auth/portal")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(AuthAccountClient.class);
    }
}
