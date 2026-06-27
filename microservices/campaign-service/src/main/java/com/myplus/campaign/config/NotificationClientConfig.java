package com.myplus.campaign.config;

import com.myplus.common.notify.NotificationClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the {@link NotificationClient} proxy (slice 33, Phase 8) — a load-balanced @HttpExchange client at
 * {@code lb://notification-service/api/notifications}, with the shared identity-forwarding interceptor for
 * authenticated callers (harmless for the public demo-lead context). Replaces campaign's own JavaMailSender.
 */
@Configuration
public class NotificationClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public NotificationClient notificationClient(@LoadBalanced RestClient.Builder builder) {
        RestClient restClient = builder
                .baseUrl("http://notification-service/api/notifications")
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(NotificationClient.class);
    }
}
