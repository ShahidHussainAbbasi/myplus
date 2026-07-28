package com.web.controller;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.service.LiveUserCountService;

/**
 * Public "users online" count for the landing and login page headers.
 *
 * Unauthenticated by design — both pages are reachable while signed out. The path is permitted in
 * {@code SecSecurityConfig}; being a GET it needs no CSRF exemption.
 *
 * Returns only a number: no identities, no session ids, nothing that would tell an anonymous caller
 * who is signed in.
 */
@Controller
public class LiveUserCountController {

    @Autowired
    private LiveUserCountService liveUserCountService;

    @GetMapping("/api/live-users")
    @ResponseBody
    public ResponseEntity<Map<String, Integer>> liveUsers() {
        final int count = liveUserCountService.getDisplayCount();

        // The service already caches for 5s; this stops a proxy or the browser pinning a stale
        // figure for longer than the poll interval.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(10)))
                .body(Collections.singletonMap("count", count));
    }
}
