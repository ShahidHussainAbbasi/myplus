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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// import jakarta.crypto.SecretKey;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
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

    private SecretKey signingKey;

    private static final List<String> OPEN_API_ENDPOINTS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/verify-email",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/campaign/public/"   // public Book-a-Demo lead capture (anonymous)
    );

    public JwtAuthenticationFilter() {
        super(Config.class);
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

                return chain.filter(exchange.mutate().request(mutated).build());
            } catch (Exception ex) {
                log.warn("JWT validation failed: {}", ex.getMessage());
                return unauthorized(exchange.getResponse(), "Invalid or expired token");
            }
        };
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
