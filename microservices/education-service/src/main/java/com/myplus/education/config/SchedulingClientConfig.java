package com.myplus.education.config;

import com.myplus.commerce.contracts.client.SchedulingClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Slice SCHED-1 (B3) — the {@link SchedulingClient} proxy, at
 * {@code lb://appointment-service/api/scheduling}.
 *
 * <p>Same shape and the same timeouts as {@link AuthAccountClientConfig}: a slow scheduling core must fail
 * fast rather than pin the thread a guardian is booking on. <b>Timeouts matter more here than on most
 * clients</b> — this one is reached from the PORTAL, so the caller is a family on a phone rather than a
 * staff member at a desk, and the platform already carries a standing finding (D3e) about a monolith client
 * with no timeouts at all.
 *
 * <p>The identity interceptor stamps the caller's org, which is what scopes every slot and booking on the
 * other side. Without it the scheduling core would see no tenant and refuse — the fail-closed direction.
 */
@Configuration
public class SchedulingClientConfig {

    @Bean
    public SchedulingClient schedulingClient(@LoadBalanced RestClient.Builder builder) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1000);
        rf.setReadTimeout(3000);
        RestClient restClient = builder.clone()
                .baseUrl("http://appointment-service/api/scheduling")
                .requestFactory(rf)
                .requestInterceptor(GatewayIdentityForwarding.interceptor())
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(SchedulingClient.class);
    }
}
