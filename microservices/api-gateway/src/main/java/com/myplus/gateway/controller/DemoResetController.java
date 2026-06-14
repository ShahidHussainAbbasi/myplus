package com.myplus.gateway.controller;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * On-demand demo reset: clears the calling demo user's write counters (Redis {@code demo:{userId}:*}),
 * so a demo account can "restart" its 50/module trial without waiting for the daily roll-over. Reached
 * via the gateway's own path (not a routed /api/** service); reads the Bearer JWT itself.
 */
@RestController
public class DemoResetController {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final ReactiveStringRedisTemplate redis;
    private SecretKey signingKey;

    public DemoResetController(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void init() {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
        } catch (DecodingException | IllegalArgumentException ex) {
            keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @PostMapping("/demo/reset")
    public Mono<ResponseEntity<Map<String, Object>>> reset(@RequestHeader("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Missing token")));
        }
        String userId;
        boolean demo;
        try {
            Claims c = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(auth.substring(7)).getPayload();
            userId = String.valueOf(c.get("userId"));
            demo = Boolean.TRUE.equals(c.get("demo", Boolean.class));
        } catch (Exception e) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid token")));
        }
        if (!demo) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", "Not a demo account")));
        }
        return redis.keys("demo:" + userId + ":*").collectList()
                .flatMap(keys -> keys.isEmpty() ? Mono.just(0L)
                        : redis.delete(keys.toArray(new String[0])))
                .map(n -> ResponseEntity.ok(Map.<String, Object>of("success", true, "cleared", n)))
                .onErrorResume(e -> Mono.just(ResponseEntity.ok(Map.of("success", true, "cleared", 0))));
    }
}
