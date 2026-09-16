package com.web.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.security.TokenStore;
import com.web.util.GatewayClient;
import com.web.util.ProxyErrors;

/**
 * SESS-1 — the header chip's two calls: how many devices this account is signed in on, and sign the rest out.
 *
 * <h3>Why the shopkeeper needs this</h3>
 * Sessions are capped ({@code jwt.max-sessions-per-user}, 5) and the cap evicts the OLDEST login to make room
 * for a new one. Until now that happened silently: the evicted device kept working for up to ~15 minutes and
 * then refused everything with "Invalid refresh token". The cap stays — the user's ruling — and the count is
 * shown instead, with a way to free a slot deliberately rather than by being evicted.
 *
 * <h3>Why no id crosses the wire</h3>
 * auth-service resolves the caller from the Bearer token alone, and the session to KEEP is the refresh token
 * this HTTP session already holds server-side ({@link TokenStore}). The browser never sees a token and never
 * names a user, so neither call can be pointed at another account by anything a client can shape.
 */
@RestController
public class SessionsProxyController {

    /** The gateway prefix auth-service is routed under — the same constant every auth proxy here uses. */
    private static final String AUTH_PREFIX = "/api/auth";

    @Autowired
    private GatewayClient gateway;

    @Autowired
    private TokenStore tokenStore;

    @Value("${auth.server.url:http://localhost:8765}")
    private String authDirectUrl;

    /** {@code {count, max, atCap, oldestSignedInAt}} for the signed-in user. */
    @GetMapping("/mySessions")
    @ResponseBody
    public Map<String, Object> mySessions() {
        try {
            Map<String, Object> answer = gateway.forMap(AUTH_PREFIX, authDirectUrl, "/sessions",
                    HttpMethod.GET, null, null);
            return unwrap(answer);
        } catch (Exception e) {
            // A chip that cannot answer must not take the dashboard down with it — but a session that has
            // ENDED still has to reach SessionExpiredAdvice, which ProxyErrors re-throws for.
            return ProxyErrors.failure(e);
        }
    }

    /**
     * Sign out every other device, keeping this one.
     *
     * <p>The refresh token is read from this session's own {@link TokenStore} and sent to auth-service as the
     * session to keep. It is never accepted from the request: a browser that could name the session to keep
     * could also name somebody else's.
     */
    @PostMapping("/revokeOtherSessions")
    @ResponseBody
    public Map<String, Object> revokeOtherSessions() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("refreshToken", tokenStore.getRefreshToken());
            Map<String, Object> answer = gateway.forMap(AUTH_PREFIX, authDirectUrl, "/sessions/revoke-others",
                    HttpMethod.POST, body, MediaType.APPLICATION_JSON);
            return unwrap(answer);
        } catch (Exception e) {
            return ProxyErrors.failure(e);
        }
    }

    /**
     * auth-service answers in the {@code ApiResponse} envelope ({@code {success, message, data}}); the chip
     * wants the figures. Unwrapped here rather than in the browser so the markup contract does not depend on
     * which service shape happens to be behind it.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrap(Map<String, Object> envelope) {
        if (envelope == null) return Map.of();
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach(out::put);
            return out;
        }
        return envelope;
    }
}
