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

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private AuthServerClient authServerClient;

    private final RestTemplate restTemplate = new RestTemplate();

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
            return restTemplate.exchange(url, method, entity, responseType);
        } catch (HttpClientErrorException.Unauthorized e) {
            // Access token likely expired — refresh once and retry (server mode only).
            if (serverMode && refreshAccessToken()) {
                HttpEntity<?> retry = new HttpEntity<>(body, buildHeaders(true, contentType));
                return restTemplate.exchange(url, method, retry, responseType);
            }
            throw e;
        }
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
