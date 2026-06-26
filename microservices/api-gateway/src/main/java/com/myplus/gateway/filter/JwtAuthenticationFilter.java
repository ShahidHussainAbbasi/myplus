package com.myplus.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// import jakarta.crypto.SecretKey;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    // Shared secret stamped on every authenticated request the gateway forwards. Downstream
    // services trust X-User-* headers only when this matches, so a leaked network position
    // cannot forge identity headers by hitting a service directly. Empty = not enforced yet.
    @Value("${gateway.internal-secret:}")
    private String internalSecret;

    // Demo free-trial cap: a demo user (JWT demo=true) may create at most this many entries per module
    // per day; the counter lives in Redis keyed by user+module+date and auto-expires at end of day.
    @Value("${demo.max-entries:50}")
    private int demoMaxEntries;

    private final ReactiveStringRedisTemplate redis;

    // POSTs whose path contains a read hint are not "entries" (list/search via POST), so they don't count.
    private static final List<String> DEMO_READ_HINTS =
            List.of("/get", "/load", "/list", "/search", "/report", "/find", "/view", "/export");

    private SecretKey signingKey;

    private static final List<String> OPEN_API_ENDPOINTS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/verify-email",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/campaign/public/",  // public Book-a-Demo lead capture (anonymous)
            "/api/appointment/public/",  // public patient booking (anonymous)
            "/api/catalog/public/",      // public storefront product browse (slice 47, anonymous)
            "/api/marketplace/public/"   // public storefront guest checkout (slice 47, anonymous)
    );

    public JwtAuthenticationFilter(ReactiveStringRedisTemplate redis) {
        super(Config.class);
        this.redis = redis;
    }

    @PostConstruct
    public void init() {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
        } catch (DecodingException | IllegalArgumentException ex) {
            // Not valid base64 (e.g. raw string or base64url with '-'/'_'): use the raw bytes.
            keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();

            if (OPEN_API_ENDPOINTS.stream().anyMatch(path::startsWith)) {
                return chain.filter(exchange);
            }

            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return unauthorized(exchange.getResponse(), "Missing Authorization header");
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return unauthorized(exchange.getResponse(), "Invalid Authorization header");
            }

            String token = authHeader.substring(7);
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(signingKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String userId = String.valueOf(claims.get("userId"));
                String email = claims.getSubject();
                Object rolesObj = claims.get("roles");
                String roles = rolesObj != null ? rolesObj.toString() : "";
                Object privilegesObj = claims.get("privileges");
                String privileges = privilegesObj != null ? privilegesObj.toString() : "";
                Object orgObj = claims.get("activeOrgId");
                String orgId = orgObj != null ? String.valueOf(orgObj) : "";

                ServerHttpRequest.Builder builder = request.mutate()
                        // F3: drop ALL client-supplied identity/secret headers before stamping our
                        // own, so a forged value can never survive (even as a duplicate header).
                        .headers(h -> {
                            h.remove("X-Internal-Secret");
                            h.remove("X-Org-Id");
                            h.remove("X-User-Id");
                            h.remove("X-User-Email");
                            h.remove("X-User-Roles");
                            h.remove("X-User-Privileges");
                        })
                        .header("X-User-Id", userId)
                        .header("X-User-Email", email != null ? email : "")
                        .header("X-User-Roles", roles)
                        .header("X-User-Privileges", privileges)
                        .header("X-Org-Id", orgId);
                if (internalSecret != null && !internalSecret.isEmpty()) {
                    builder.header("X-Internal-Secret", internalSecret);
                }
                ServerHttpRequest mutated = builder.build();
                ServerWebExchange forwarded = exchange.mutate().request(mutated).build();

                // Tenant entitlement enforcement (slice 32). Plan/cap/trial come from the JWT; the legacy
                // `demo` boolean still caps already-issued tokens and seeded demo users (non-breaking).
                boolean demo = Boolean.TRUE.equals(claims.get("demo", Boolean.class));
                String plan = claims.get("plan", String.class);
                Integer entryCap = claims.get("entryCap", Integer.class);
                // Effective per-module write cap: an explicit tenant cap (DEMO tenants) wins; otherwise the
                // legacy demo flag still enforces the default. null => uncapped (FREE/PRO/active TRIAL).
                Integer cap = entryCap;
                if (cap == null && demo) {
                    cap = demoMaxEntries;
                }
                boolean writeAttempt = HttpMethod.POST.equals(request.getMethod()) && isCreate(path);

                // A TRIAL whose window has closed blocks writes (reads still flow so they can view + upgrade).
                if (writeAttempt && isTrialExpired(plan, claims.get("trialEndsAt", String.class))) {
                    return trialExpired(exchange.getResponse());
                }

                // Capped plans (DEMO / legacy demo): count create POSTs per (user, module) per day in Redis.
                if (cap != null && writeAttempt) {
                    final int limit = cap;
                    String key = "demo:" + userId + ":" + moduleOf(path) + ":" + LocalDate.now();
                    return redis.opsForValue().increment(key)
                            .flatMap(count -> (count != null && count == 1L)
                                    ? redis.expire(key, Duration.ofSeconds(secondsToEndOfDay())).thenReturn(count)
                                    : Mono.just(count == null ? 0L : count))
                            .flatMap(count -> (count > limit)
                                    ? capLimit(exchange.getResponse(), limit)
                                    : chain.filter(forwarded))
                            .onErrorResume(e -> {
                                // Fail-open: never let a Redis hiccup break the request path.
                                log.warn("entry quota check failed (allowing request): {}", e.getMessage());
                                return chain.filter(forwarded);
                            });
                }

                return chain.filter(forwarded);
            } catch (Exception ex) {
                log.warn("JWT validation failed: {}", ex.getMessage());
                return unauthorized(exchange.getResponse(), "Invalid or expired token");
            }
        };
    }

    /** The module segment of an /api/&lt;module&gt;/... path (so the demo cap is independent per module). */
    private String moduleOf(String path) {
        String[] seg = path.split("/");
        return seg.length > 2 ? seg[2] : "unknown";
    }

    /** A create-style POST counts toward the demo cap; reads-via-POST (list/search/…) do not. */
    private boolean isCreate(String path) {
        String p = path.toLowerCase();
        return DEMO_READ_HINTS.stream().noneMatch(p::contains);
    }

    /** Seconds until local midnight — the demo counter expires then, so the cap auto-resets daily. */
    private long secondsToEndOfDay() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        return Math.max(60, Duration.between(now, endOfDay).getSeconds());
    }

    /** A TRIAL whose {@code trialEndsAt} is in the past; reads still flow, writes are blocked. Fail-open on parse. */
    private boolean isTrialExpired(String plan, String trialEndsAt) {
        if (!"TRIAL".equals(plan) || trialEndsAt == null) {
            return false;
        }
        try {
            return java.time.LocalDateTime.parse(trialEndsAt).isBefore(java.time.LocalDateTime.now());
        } catch (Exception e) {
            return false; // unparseable timestamp -> don't block
        }
    }

    // Keeps the literal DEMO_LIMIT code so the monolith's GatewayClient/DemoLimitAdvice relays it as the
    // upsell modal (it matches the substring + extracts "message"); the message carries the actual cap.
    private Mono<Void> capLimit(ServerHttpResponse response, int cap) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add("Content-Type", "application/json");
        String body = "{\"success\":false,\"code\":\"DEMO_LIMIT\",\"message\":\"You've reached the " + cap
                + "-entry limit. Register at maxtheservice.com to unlock the full features.\",\"statusCode\":403}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    // Trial-window close. Reuses the DEMO_LIMIT code (so the existing UI upsell fires without a monolith
    // change) but carries a trial-specific message + reason for future differentiation.
    private Mono<Void> trialExpired(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add("Content-Type", "application/json");
        String body = "{\"success\":false,\"code\":\"DEMO_LIMIT\",\"reason\":\"TRIAL_EXPIRED\",\"message\":\""
                + "Your free trial has ended. Upgrade at maxtheservice.com to continue.\",\"statusCode\":403}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json");
        String body = "{\"success\":false,\"message\":\"" + message + "\",\"statusCode\":401}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    public static class Config {
    }
}
