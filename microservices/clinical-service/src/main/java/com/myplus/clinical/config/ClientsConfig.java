package com.myplus.clinical.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * The three services clinical-service talks to, through the shared contracts: party-service (the person),
 * business-service (the pharmacy customer) and audit-service (the trail). Each forwards the caller's identity, so
 * the far side scopes to the same clinic, and each has short timeouts (STANDARDS D3e).
 *
 * <p>The two linking calls run AFTER the patient commits: if either service is slow or down, the patient is still
 * registered and shown with "customer link pending", and the link is retried — never a lost registration.
 */
@Configuration
public class ClientsConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    private static SimpleClientHttpRequestFactory timeouts(int readMs) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1500);
        rf.setReadTimeout(readMs);
        return rf;
    }

    private static <T> T client(RestClient.Builder builder, String base, int readMs, Class<T> type) {
        RestClient rc = builder.clone()
                .baseUrl(base)
                .requestFactory(timeouts(readMs))
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rc)).build().createClient(type);
    }

    @Bean
    public PartyClient partyClient(@LoadBalanced RestClient.Builder builder) {
        return client(builder, "http://party-service/api/party/parties", 3000, PartyClient.class);
    }

    @Bean
    public TradeClient tradeClient(@LoadBalanced RestClient.Builder builder) {
        return client(builder, "http://business-service", 5000, TradeClient.class);
    }

    /** HMS S2 — the doctors. Read once per screen load (the token snapshots the name), never per board row. */
    @Bean
    public AppointmentDirectoryClient appointmentDirectoryClient(@LoadBalanced RestClient.Builder builder) {
        return client(builder, "http://appointment-service", 4000, AppointmentDirectoryClient.class);
    }

    @Bean
    public AuditClient auditClient(@LoadBalanced RestClient.Builder builder) {
        return client(builder, "http://audit-service/api/audit", 5000, AuditClient.class);
    }
}
