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
            "X-User-Id", "X-User-Email", "X-User-Roles", "X-User-Privileges", "X-Org-Id",
            "X-Location-Id", "X-Location-Ids", "X-Loc-Role", "X-Internal-Secret",
            // C3c: carried service-to-service so a hop keeps the SAME capability answer the first callee had.
            // Omitting it here would make an inter-service call fall back to the callee's local settings
            // store — reintroducing the very divergence the JWT claim exists to remove, on exactly the paths
            // (saga steps, relays) where it would be hardest to notice.
            "X-Org-Caps");

    private GatewayIdentityForwarding() {}

    /** Explicit identity for background jobs (no inbound request) — e.g. the saga recovery relay (U3c). */
    private static final ThreadLocal<Map<String, String>> RUN_AS = new ThreadLocal<>();

    /**
     * The internal trust secret this service was configured with ({@code service.internal-secret}). {@link #runAs}
     * stamps it as {@code X-Internal-Secret} so background/relay calls authenticate against a callee that ENFORCES
     * the secret (a runAs call has no inbound request to copy it from). Configured once at startup by common-security.
     */
    private static volatile String internalSecret = "";

    /** Set at startup from {@code service.internal-secret} (see CommonSecurityAutoConfiguration). */
    public static void configureInternalSecret(String secret) {
        internalSecret = (secret == null) ? "" : secret;
    }

    /**
     * Run {@code action} as the given tenant/user so outbound service calls inside it carry that identity
     * (the {@link #interceptor()} reads this override when there is no inbound request). For background jobs.
     */
    public static void runAs(Long userId, Long organizationId, Runnable action) {
        Map<String, String> headers = new HashMap<>();
        if (userId != null) headers.put("X-User-Id", String.valueOf(userId));
        if (organizationId != null) headers.put("X-Org-Id", String.valueOf(organizationId));
        // Carry the internal trust secret too, else a callee that enforces service.internal-secret rejects the
        // secret-less runAs call with 401 (the inbound-request path forwards it, but runAs has no inbound request).
        if (!internalSecret.isEmpty()) headers.put("X-Internal-Secret", internalSecret);
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
