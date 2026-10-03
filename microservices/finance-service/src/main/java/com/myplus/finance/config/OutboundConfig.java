package com.myplus.finance.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * FP-4c — finance's first outbound call (to business-service, through Eureka) and its first schedule (the outbox
 * relay's retry). Scheduling was off in finance until now; the only {@code @Scheduled} method on its classpath is
 * {@code PayableBalanceService.flushPending} (common-outbox's relay has none of its own), so enabling it starts that
 * and nothing else.
 */
@Configuration
@EnableScheduling
public class OutboundConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
