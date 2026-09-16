package com.myplus.auth.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.auth.dto.ApiResponse;
import com.myplus.auth.service.JwtService;
import com.myplus.auth.service.RefreshTokenService;

import lombok.RequiredArgsConstructor;

/**
 * SESS-1 — a signed-in user's own devices: how many, and a way to clear the rest.
 *
 * <h3>Why this exists</h3>
 * A production shopkeeper's sale was refused with {@code Invalid refresh token} → 401 (2026-09-16). The
 * session cap ({@code jwt.max-sessions-per-user}, 5) had evicted their oldest login to make room for a
 * sixth, and nothing on any screen said so. The user's ruling was to KEEP the cap at 5 and make it visible
 * instead — which is the policy the monolith's own {@code SecSecurityConfig} already states: "security comes
 * from VISIBILITY over those sessions, not from a hard cap".
 *
 * <h3>Why neither endpoint takes an id — this is the whole security design</h3>
 * The caller is resolved from the BEARER TOKEN and from nothing else, exactly as
 * {@link SupportSessionController} does. There is no {@code userId} path variable, query parameter or body
 * field to tamper with, so "show me my sessions" and "sign out my other devices" cannot be pointed at
 * another account by any request a client can shape. A guard clause could be removed by someone tidying up;
 * an absent parameter cannot.
 *
 * <p>Both paths sit under {@code /api/auth/**}, which {@code SecurityConfig} leaves
 * {@code .anyRequest().authenticated()} — no {@code permitAll} entry may ever be added for them.
 */
@RestController
@RequiredArgsConstructor
public class SessionsController {

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    /**
     * This account's live sessions: {@code {count, max, atCap, oldestSignedInAt}}.
     *
     * <p>A count and a timestamp, deliberately — {@code refresh_tokens} carries no device name, browser or
     * last-used column, so anything richer would be invented rather than reported.
     */
    @GetMapping("/api/auth/sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mine(@RequestHeader("Authorization") String auth) {
        Long userId = jwtService.extractUserId(bearer(auth));
        return ResponseEntity.ok(ApiResponse.success(refreshTokenService.describeSessions(userId)));
    }

    /**
     * Sign out every other device, keeping the session that asked.
     *
     * <p>The session to keep is identified by the refresh token the CALLER already holds server-side. The
     * monolith sends it from its session-scoped {@code TokenStore}; the browser never sees it, and there is
     * no form of this call that signs out somebody else.
     *
     * <p>Body: {@code {refreshToken}}. Answers {@code {signedOut: n}} so the screen can say what it did.
     */
    @PostMapping("/api/auth/sessions/revoke-others")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeOthers(
            @RequestHeader("Authorization") String auth,
            @RequestBody(required = false) Map<String, Object> body) {

        Long userId = jwtService.extractUserId(bearer(auth));
        String keep = (body == null) ? null : str(body.get("refreshToken"));

        int removed = refreshTokenService.revokeOtherSessions(userId, keep);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signedOut", removed);
        out.putAll(refreshTokenService.describeSessions(userId));   // the fresh count, so the chip needs no second call
        return ResponseEntity.ok(ApiResponse.success(out, "Other devices signed out"));
    }

    /** The raw token from an {@code Authorization: Bearer …} header. */
    private static String bearer(String header) {
        return (header != null && header.startsWith("Bearer ")) ? header.substring(7) : header;
    }

    private static String str(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }
}
