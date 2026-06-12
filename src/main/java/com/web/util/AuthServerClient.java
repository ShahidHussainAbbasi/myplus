package com.web.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

    /**
     * Trigger a password-reset email for {@code email}. The auth-service owns the reset token and
     * sends the mail; it returns 404 for an unknown address. Callers should swallow failures so the
     * UI never reveals whether an address is registered.
     */
    public void forgotPassword(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        restTemplate.postForEntity(baseUrl + "/api/auth/forgot-password", new HttpEntity<>(body, headers), Void.class);
    }

    /**
     * Complete a password reset using the token from the reset email. Throws
     * {@link org.springframework.web.client.HttpStatusCodeException} if the token is invalid/expired
     * or the new password is rejected by the auth-service.
     */
    public void resetPassword(String token, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("token", token);
        body.put("newPassword", newPassword);
        restTemplate.postForEntity(baseUrl + "/api/auth/reset-password", new HttpEntity<>(body, headers), Void.class);
    }

    /**
     * Register a new account at the auth-service (the single identity store). The auth-service
     * persists the user (disabled until verified) and sends the verification email. Throws
     * {@link org.springframework.web.client.HttpStatusCodeException} on duplicate email / validation.
     */
    public void register(String firstName, String lastName, String email, String password, String phone, String userType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", email);
        body.put("password", password);
        if (phone != null) body.put("phone", phone);
        if (userType != null) body.put("userType", userType);
        restTemplate.postForEntity(baseUrl + "/api/auth/register", new HttpEntity<>(body, headers), Void.class);
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

    /** List the organizations the token's user belongs to (each: id, name, role, active). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> organizations(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/api/auth/organizations", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Object data = resp.getBody() == null ? null : resp.getBody().get("data");
        return data instanceof List ? (List<Map<String, Object>>) data : List.of();
    }

    /**
     * Change the logged-in user's password (auth-service owns the identity store). Bearer-authenticated;
     * auth-service resolves the user from the token. Throws
     * {@link org.springframework.web.client.HttpStatusCodeException} if the current password is wrong
     * or the new password is rejected.
     */
    public void changePassword(String accessToken, String currentPassword, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        restTemplate.exchange(baseUrl + "/api/auth/users/me/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Void.class);
    }

    /** Begin 2FA enrolment; returns the {@code otpauth://} provisioning URI to render as a QR code. */
    @SuppressWarnings("unchecked")
    public String setup2fa(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/api/auth/2fa/setup", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        Object data = resp.getBody() == null ? null : resp.getBody().get("data");
        return data instanceof Map ? (String) ((Map<String, Object>) data).get("qrUrl") : null;
    }

    /** Verify the authenticator code during 2FA enrolment; {@code true} if it matches. */
    @SuppressWarnings("unchecked")
    public boolean verify2fa(String accessToken, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("code", code);
        ResponseEntity<Map> resp = restTemplate.exchange(
                baseUrl + "/api/auth/2fa/verify", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Object data = resp.getBody() == null ? null : resp.getBody().get("data");
        return data instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) data).get("verified"));
    }

    /** Disable 2FA for the logged-in user. */
    public void disable2fa(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        restTemplate.exchange(baseUrl + "/api/auth/2fa/disable", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    /** Switch the active organization; returns fresh tokens scoped to {@code organizationId}. */
    public AuthServerLoginResponse switchOrganization(String accessToken, Long organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("organizationId", organizationId);
        ResponseEntity<AuthServerEnvelope> resp = restTemplate.exchange(
                baseUrl + "/api/auth/switch-organization", HttpMethod.POST,
                new HttpEntity<>(body, headers), AuthServerEnvelope.class);
        AuthServerEnvelope envelope = resp.getBody();
        return envelope == null ? null : envelope.getData();
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
