package com.myplus.auth.config;

import com.myplus.common.notify.NotificationClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the {@link NotificationClient} proxy (slice 33, Phase 8) — load-balanced @HttpExchange at
 * {@code lb://notification-service/api/notifications}. Replaces auth-service's own JavaMailSender so SMTP
 * lives in one place.
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
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(NotificationClient.class);
    }

}
