package com.myplus.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

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

    /** An interceptor that copies the inbound identity headers onto the outbound request. */
    public static ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
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
