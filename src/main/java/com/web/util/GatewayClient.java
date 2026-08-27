package com.web.util;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.persistence.model.User;
import com.security.TokenStore;
import com.web.dto.AuthServerLoginResponse;

/**
 * Single outbound client for all calls to the microservices. Centralises everything that used to
 * be copied across the per-service REST clients:
 *
 * <ul>
 *   <li><b>server mode</b> (a JWT is present in the session {@link TokenStore}): calls the API
 *       gateway at {@code gateway.url + servicePrefix + path} with {@code Authorization: Bearer},
 *       and on a 401 refreshes the access token once and retries. The gateway validates the JWT
 *       and injects the X-User-* identity headers downstream.</li>
 *   <li><b>legacy mode</b> (no JWT — i.e. auth.mode=local): calls the service directly at its
 *       {@code directBaseUrl + path} and forwards the caller identity as X-User-* headers, exactly
 *       as before, so nothing breaks until the JWT cutover.</li>
 * </ul>
 *
 * The per-service facades ({@code BusinessRestClient}, {@code EducationRestClient}) only supply the
 * service prefix, the legacy base URL, and their method signatures.
 */
@Component
public class GatewayClient {

    private static final ParameterizedTypeReference<String> STRING_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Boolean> BOOLEAN_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Thread-safe once configured; used only to read the {@code message} out of an upstream error envelope. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The internal trust secret (security finding F18). Same property the services read
     * ({@code service.internal-secret}), so ONE environment variable configures the whole platform rather
     * than the monolith needing a second one that could drift out of step.
     *
     * <p>Empty by default, which is the current state and changes nothing.
     */
    @Value("${service.internal-secret:}")
    private String internalSecret;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private AuthServerClient authServerClient;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Added 2026-08-02 while diagnosing a timetable failure that took hours to pin down.
     *
     * <p>Every downstream error used to vanish here: {@link #execute} handles 401 and 403 and lets
     * everything else propagate, so the monolith's catch-all handler turned it into a generic
     * {@code "Error Occurred"} 500. The service's real status, message and body — the only things that
     * say what actually went wrong — were never recorded anywhere. That is true for EVERY module's
     * proxy, not just education.
     *
     * <p>This logger changes no behaviour. It writes down what the downstream said, then the exception
     * propagates exactly as before.
     */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GatewayClient.class);

    // ---- Public, return-type-specific entry points ----

    public ResponseEntity<String> forStringEntity(String servicePrefix, String directBaseUrl, String path,
                                                   HttpMethod method, Object body, MediaType contentType) {
        return execute(servicePrefix, directBaseUrl, path, method, body, contentType, STRING_TYPE);
    }

    public String forString(String servicePrefix, String directBaseUrl, String path,
                            HttpMethod method, Object body, MediaType contentType) {
        return forStringEntity(servicePrefix, directBaseUrl, path, method, body, contentType).getBody();
    }

    public Map<String, Object> forMap(String servicePrefix, String directBaseUrl, String path,
                                      HttpMethod method, Object body, MediaType contentType) {
        return execute(servicePrefix, directBaseUrl, path, method, body, contentType, MAP_TYPE).getBody();
    }

    public Boolean forBoolean(String servicePrefix, String directBaseUrl, String path,
                              HttpMethod method, Object body, MediaType contentType) {
        return execute(servicePrefix, directBaseUrl, path, method, body, contentType, BOOLEAN_TYPE).getBody();
    }

    // ---- Core: URL + auth/headers + refresh-on-401 ----

    private <T> ResponseEntity<T> execute(String servicePrefix, String directBaseUrl, String path,
                                          HttpMethod method, Object body, MediaType contentType,
                                          ParameterizedTypeReference<T> responseType) {
        boolean serverMode = tokenStore.hasAccessToken();
        String url = (serverMode ? gatewayUrl + servicePrefix : directBaseUrl) + path;

        HttpEntity<?> entity = new HttpEntity<>(body, buildHeaders(serverMode, contentType));
        try {
            return strip(restTemplate.exchange(url, method, entity, responseType));
        } catch (HttpClientErrorException.Unauthorized e) {
            // Access token likely expired — refresh once and retry (server mode only).
            if (serverMode && refreshAccessToken()) {
                HttpEntity<?> retry = new HttpEntity<>(body, buildHeaders(true, contentType));
                return strip(restTemplate.exchange(url, method, retry, responseType));
            }
            throw e;
        } catch (HttpClientErrorException.Forbidden e) {
            // Demo free-trial cap: surface the gateway's DEMO_LIMIT as a typed exception so the UI shows
            // the "register at maxtheservice.com" upsell instead of a generic failure.
            String respBody = e.getResponseBodyAsString();
            if (respBody != null && respBody.contains("DEMO_LIMIT")) {
                throw new com.web.error.DemoLimitException(extractDemoMessage(respBody));
            }
            log.warn("Downstream FORBIDDEN {} {} -> 403; body={}", method, url, abbreviate(respBody));
            throw e;
        } catch (HttpClientErrorException.NotFound e) {
            // Slice 3.1b. A downstream 404 is an ANSWER, not a failure, and it must reach the browser as
            // one. Previously it fell through to the catch-all handler and became a generic 500
            // "Error Occurred" — the same defect slice 2.1 found for other statuses (standard D3d), still
            // present for this one.
            //
            // It matters here specifically: PortalScopeFilter refuses a portal principal with 404, so
            // without this a guardian probing a staff endpoint got a 500. That still protected the data —
            // education-service had already refused — but a 500 says "something broke", which is both
            // untrue and more informative to a prober than the deliberate silence 404 was chosen for.
            log.warn("Downstream NOT FOUND {} {} -> 404; body={}", method, url,
                    abbreviate(e.getResponseBodyAsString()));
            throw new com.web.error.DownstreamNotFoundException(e.getResponseBodyAsString());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Every other 4xx/5xx. Previously invisible: it propagated to the monolith's catch-all
            // handler, which replaced it with a generic "Error Occurred" 500 — so the service's own
            // message never reached the log OR the browser.
            log.warn("Downstream FAILED {} {} -> {}; body={}",
                    method, url, e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw e;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Transport failure: connect refused, or a read that never returned. Worth its own line
            // because the RestTemplate below has NO timeouts, so this is the shape a hung downstream
            // takes — and it is indistinguishable from an application error without this log.
            log.warn("Downstream UNREACHABLE {} {} -> {}", method, url, e.getMessage());
            throw e;
        }
    }

    /** Response bodies can be large; enough to identify the error, not enough to flood the log. */
    private static String abbreviate(String s) {
        if (s == null) return "(none)";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 500 ? flat : flat.substring(0, 500) + "…";
    }

    private static final java.util.Set<String> HOP_BY_HOP = java.util.Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length");

    /**
     * Drop hop-by-hop headers from a proxied response so the servlet container generates its own
     * framing. Otherwise the gateway's Transfer-Encoding is relayed verbatim and Tomcat adds a
     * second one, producing a duplicate Transfer-Encoding header that nginx rejects with 502.
     */
    private static <T> ResponseEntity<T> strip(ResponseEntity<T> upstream) {
        HttpHeaders clean = new HttpHeaders();
        upstream.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                clean.put(name, values);
            }
        });
        return new ResponseEntity<>(upstream.getBody(), clean, upstream.getStatusCode());
    }

    private static String extractDemoMessage(String body) {
        String msg = extractMessage(body);
        return msg != null ? msg
                : "You've reached the 50-entry demo limit. Register at maxtheservice.com to unlock the full features.";
    }

    /**
     * Pull {@code "message"} out of an upstream {@code ApiResponse} envelope; null if there isn't one.
     *
     * Parsed, NOT pattern-matched: the previous regex ({@code "message"\s*:\s*"([^"]*)"}) stopped at the first
     * escaped quote, so a perfectly ordinary service message like {@code "Paracetamol" needs a quantity} arrived
     * as a lone backslash. Any message containing a quote, backslash or newline hit it.
     */
    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(body).path("message");
            return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
        } catch (Exception e) {
            return null;   // not JSON (HTML error page, empty body) — caller falls back to its own wording
        }
    }

    /**
     * Turn a failed proxied call into the envelope the dashboard JS already understands
     * ({@code {success:false, message:...}}), keeping the SERVICE's own message where there is one.
     *
     * Without this a proxy's {@code catch (Exception)} collapses every upstream 4xx into a bare
     * {@code {success:false}}, so a deliberate, actionable rejection ("This prescription expired on
     * 2026-05-01 and cannot be dispensed.") reaches the user as a generic "could not save".
     */
    public static Map<String, Object> errorMap(Exception e, String fallbackMessage) {
        String message = (e instanceof HttpClientErrorException he) ? extractMessage(he.getResponseBodyAsString()) : null;
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("success", false);
        out.put("message", message != null ? message : fallbackMessage);
        return out;
    }

    private HttpHeaders buildHeaders(boolean serverMode, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        if (serverMode) {
            headers.setBearerAuth(tokenStore.getAccessToken());
        } else {
            // Legacy: gateway isn't in the path, so forward identity ourselves (pre-JWT behaviour).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                headers.set("X-User-Id", String.valueOf(user.getId()));
                headers.set("X-User-Email", user.getEmail());
                headers.set("X-User-Roles", auth.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .collect(java.util.stream.Collectors.joining(",")));
            }
            // ── SECURITY FINDING F18 (fixed 2026-08-09) ──────────────────────────────────────────
            //
            // The monolith calls services DIRECTLY, bypassing the gateway, and never stamped this — which
            // is why `service.internal-secret` cannot be switched on platform-wide today:
            //
            //   HeaderAuthFilter: secret configured AND header does not match
            //                     -> identity headers are IGNORED and the request proceeds unauthenticated
            //
            // So the moment anyone set INTERNAL_SECRET, every screen the monolith proxies would lose its
            // user and its org — the whole education, welfare and agriculture UI — while looking like an
            // authorisation problem rather than a missing header.
            //
            // It also blocks slice 3.1b's portal provisioning outright: auth-service's
            // PortalAccountController FAILS CLOSED on an unset secret, so `invitePortalAccess` writes the
            // access row and never creates the sign-in. A feature the programme records as done does not
            // actually work locally, and this header is the reason.
            //
            // **No behaviour change while the secret is empty** (the default): nothing is stamped, exactly
            // as before. Setting it is what turns enforcement on — and that is now safe to do, which it
            // was not before this line existed. Sequenced F18 -> F2, as the security review required.
            if (internalSecret != null && !internalSecret.isEmpty()) {
                headers.set("X-Internal-Secret", internalSecret);
            }
        }
        return headers;
    }

    /**
     * Exchange the stored refresh token for a fresh access token.
     *
     * <p><b>Every exit logs.</b> This method used to return {@code false} silently from all three of its
     * failure paths, and the only thing the operator ever saw was whatever the CALLER made of the
     * resulting 401 — for the GL proxy that was a bare {@code {"status":"ERROR"}} with no cause anywhere
     * in the logs. The refusal and the symptom were in different files, and nothing connected them.
     * A session that cannot refresh is the single most common cause of "the app randomly stopped
     * working", so it has to say so.
     */
    /**
     * Mint a fresh access token for this session, on purpose rather than in response to a 401.
     *
     * <h3>Why a capability change needs this</h3>
     * C3c resolves a tenant's capabilities when its token is minted and carries them as a claim, so that no
     * service has to make a remote call to ask. The cost is staleness: an owner who switches a capability off
     * would keep the old answer until their token next changed — the screen would not update, the endpoint
     * would not refuse, and the switch would look broken.
     *
     * <p>Refreshing immediately after the write closes that window for the person who made the change, which
     * is the only session where the delay would be noticed as a bug rather than as eventual consistency.
     * {@code AuthService.refreshToken} rebuilds the claims from scratch, so the new token carries the new
     * capabilities.
     *
     * @return true if a new token was obtained
     */
    public boolean refreshNow() {
        return refreshAccessToken();
    }

    private boolean refreshAccessToken() {
        String refreshToken = tokenStore.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            log.warn("Token refresh skipped: no refresh token in this session — the caller will see a 401.");
            return false;
        }
        try {
            AuthServerLoginResponse refreshed = authServerClient.refresh(refreshToken);
            if (refreshed == null || refreshed.getAccessToken() == null) {
                log.warn("Token refresh returned no access token; the session cannot be revived and the "
                        + "caller will see a 401.");
                return false;
            }
            tokenStore.setAccessToken(refreshed.getAccessToken());
            if (refreshed.getRefreshToken() != null) {
                tokenStore.setRefreshToken(refreshed.getRefreshToken());
            }
            log.debug("Access token refreshed.");
            return true;
        } catch (Exception e) {
            // "Invalid refresh token" here means auth-service does not recognise the token this session
            // holds. Named explicitly because it is the fingerprint of a session that was displaced.
            log.warn("Token refresh FAILED ({}): {}. The caller will see a 401.",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}
