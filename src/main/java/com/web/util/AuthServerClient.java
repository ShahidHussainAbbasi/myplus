package com.web.util;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.web.dto.AuthServerEnvelope;
import com.web.dto.AuthServerLoginResponse;

/**
 * Thin client for the auth-service authentication endpoints, reached through the API gateway
 * ({@code /api/auth/**} is open — no JWT filter). Used by {@link com.security.AuthServerAuthenticationProvider}
 * to delegate credential checks and by downstream clients to refresh expired access tokens.
 */
@Component
public class AuthServerClient {

    @Value("${auth.server.url:http://localhost:8765}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /** Delegate a login. Throws {@link org.springframework.web.client.RestClientException} on transport/HTTP errors. */
    public AuthServerLoginResponse login(String email, String password, String twoFactorCode) {
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (twoFactorCode != null && !twoFactorCode.isBlank()) {
            body.put("twoFactorCode", twoFactorCode);
        }
        return post("/api/auth/login", body);
    }

    /** Exchange a refresh token for a fresh access token. */
    public AuthServerLoginResponse refresh(String refreshToken) {
        Map<String, String> body = new HashMap<>();
        body.put("refreshToken", refreshToken);
        return post("/api/auth/refresh", body);
    }

    /** Revoke the user's refresh token(s). Requires the access token (auth-service resolves the user from it). */
    public void logout(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        restTemplate.postForEntity(baseUrl + "/api/auth/logout", new HttpEntity<>(null, headers), Void.class);
    }

    private AuthServerLoginResponse post(String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthServerEnvelope> resp = restTemplate.postForEntity(
                baseUrl + path, new HttpEntity<>(body, headers), AuthServerEnvelope.class);
        AuthServerEnvelope envelope = resp.getBody();
        return envelope == null ? null : envelope.getData();
    }
}
