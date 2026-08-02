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
        }
        return headers;
    }

    private boolean refreshAccessToken() {
        String refreshToken = tokenStore.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            return false;
        }
        try {
            AuthServerLoginResponse refreshed = authServerClient.refresh(refreshToken);
            if (refreshed == null || refreshed.getAccessToken() == null) {
                return false;
            }
            tokenStore.setAccessToken(refreshed.getAccessToken());
            if (refreshed.getRefreshToken() != null) {
                tokenStore.setRefreshToken(refreshed.getRefreshToken());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
