package com.myplus.gateway.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Circuit-breaker fallback (slice 27 / tech-debt #14). When a route's breaker is open or the downstream
 * times out, the CircuitBreaker filter forwards here so the caller gets a clean 503 (flat
 * {@code GenericResponse}-shaped JSON) instead of a hang or a raw error.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback")
    public Mono<ResponseEntity<Map<String, Object>>> fallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", "ERROR",
                        "message", "Service temporarily unavailable. Please try again shortly.")));
    }
}
