package com.myplus.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-propagates the gateway's identity headers from the current inbound request onto an outbound
 * service-to-service call (slice 33). Inter-service calls bypass the gateway, so without this the callee's
 * {@link HeaderAuthFilter} sees no caller and the request is anonymous/unscoped. Shared by every service's
 * HTTP-client config (catalog/inventory/trade) so the forwarding rule lives in one place.
 */
public final class GatewayIdentityForwarding {

    /** Identity headers the gateway stamps; the callee's HeaderAuthFilter authenticates + scopes from these. */
    private static final List<String> HEADERS = List.of(
            "X-User-Id", "X-User-Email", "X-User-Roles", "X-User-Privileges", "X-Org-Id", "X-Internal-Secret");

    private GatewayIdentityForwarding() {}

    /** Explicit identity for background jobs (no inbound request) — e.g. the saga recovery relay (U3c). */
    private static final ThreadLocal<Map<String, String>> RUN_AS = new ThreadLocal<>();

    /**
     * Run {@code action} as the given tenant/user so outbound service calls inside it carry that identity
     * (the {@link #interceptor()} reads this override when there is no inbound request). For background jobs.
     */
    public static void runAs(Long userId, Long organizationId, Runnable action) {
        Map<String, String> headers = new HashMap<>();
        if (userId != null) headers.put("X-User-Id", String.valueOf(userId));
        if (organizationId != null) headers.put("X-Org-Id", String.valueOf(organizationId));
        RUN_AS.set(headers);
        try {
            action.run();
        } finally {
            RUN_AS.remove();
        }
    }

    /** Interceptor: copies identity onto the outbound request — from a {@link #runAs} override if set,
     *  otherwise from the inbound request's gateway headers. */
    public static ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            Map<String, String> override = RUN_AS.get();
            if (override != null) {
                override.forEach((k, v) -> {
                    if (!request.getHeaders().containsKey(k)) request.getHeaders().add(k, v);
                });
            } else if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest inbound = attrs.getRequest();
                for (String h : HEADERS) {
                    String v = inbound.getHeader(h);
                    if (v != null && !request.getHeaders().containsKey(h)) {
                        request.getHeaders().add(h, v);
                    }
                }
            }
            return execution.execute(request, body);
        };
    }
}
