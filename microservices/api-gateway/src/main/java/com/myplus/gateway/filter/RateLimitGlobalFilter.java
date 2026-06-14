package com.myplus.gateway.filter;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Per-user fixed-window rate limiter (tech-debt #14 follow-up to slice 27's circuit breaker).
 *
 * Keys on the caller's bearer token — the user identity the monolith forwards, present on the incoming
 * request, so this needs no dependency on the per-route JwtAuthenticationFilter ordering — and falls
 * back to client IP for unauthenticated requests. A burst above the per-second limit gets 429.
 *
 * In-memory ⇒ per-gateway-instance. For a multi-instance gateway switch to the Redis-backed
 * RequestRateLimiter (the redis-reactive dep is already present). Togglable/tunable via
 * gateway.ratelimit.enabled / gateway.ratelimit.requests-per-second.
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    @Value("${gateway.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${gateway.ratelimit.requests-per-second:100}")
    private int limit;

    // key -> [windowEpochSecond, countInWindow]
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String key = clientKey(exchange.getRequest());
        long nowSec = System.currentTimeMillis() / 1000L;
        long[] window = windows.compute(key, (k, cur) -> {
            if (cur == null || cur[0] != nowSec) {
                return new long[] { nowSec, 1L };
            }
            cur[1]++;
            return cur;
        });
        if (window[1] > limit) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        // opportunistic cleanup so distinct tokens/IPs can't grow the map unbounded
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue()[0] != nowSec);
        }
        return chain.filter(exchange);
    }

    private String clientKey(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && !auth.isBlank()) {
            return "u:" + Integer.toHexString(auth.hashCode()); // per-user (per-JWT)
        }
        String fwd = request.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return "ip:" + fwd.split(",")[0].trim();
        }
        return "ip:" + (request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50; // reject early, before routing / circuit breaker
    }
}
