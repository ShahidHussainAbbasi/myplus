package com.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import com.persistence.model.User;
import com.security.google2fa.CustomWebAuthenticationDetails;
import com.web.dto.AuthServerLoginResponse;
import com.web.util.AuthServerClient;

/**
 * Delegates credential verification to the auth-service (the JWT IdP) instead of the local DB.
 * On success it rebuilds the monolith's existing {@link User} principal and a privilege-based
 * authority set from the response (Model A), and stashes the JWT in the session-scoped
 * {@link TokenStore} for downstream gateway calls. Activated when {@code auth.mode=server}.
 */
public class AuthServerAuthenticationProvider implements AuthenticationProvider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuthServerAuthenticationProvider.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final AuthServerClient authServerClient;
    private final TokenStore tokenStore;

    public AuthServerAuthenticationProvider(AuthServerClient authServerClient, TokenStore tokenStore) {
        this.authServerClient = authServerClient;
        this.tokenStore = tokenStore;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String password = authentication.getCredentials() == null ? null : authentication.getCredentials().toString();
        String verificationCode = null;
        String captchaResponse = null;
        if (authentication.getDetails() instanceof CustomWebAuthenticationDetails details) {
            verificationCode = details.getVerificationCode();
            captchaResponse = details.getCaptchaResponse();
        }

        AuthServerLoginResponse response;
        try {
            // The captcha token is forwarded to the auth-service, which is now the single verification
            // point (reCAPTCHA tokens are single-use, so the monolith no longer validates locally).
            response = authServerClient.login(email, password, verificationCode, captchaResponse);
        } catch (HttpStatusCodeException e) {
            // auth-service names WHY in the refusal's `reason` code (SignInFailure) — branch on that, never on its
            // English `message`. A 5xx carries no code: auth-service itself is in trouble, not the person.
            if (e.getStatusCode().is5xxServerError()) {
                LOG.warn("Sign-in: auth-service answered {}", e.getStatusCode());
                throw new SignInFailedException(SignInFailure.AUTH_UNAVAILABLE, e);
            }
            throw new SignInFailedException(SignInFailure.fromRefusal(refusalCode(e.getResponseBodyAsString())), e);
        } catch (RestClientException e) {
            // Logged: this branch used to be silent, and it hid every 401 reason behind "Invalid Credentials"
            LOG.warn("Sign-in could not reach auth-service ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            throw new SignInFailedException(SignInFailure.AUTH_UNAVAILABLE, e);
        }

        if (response == null) {
            LOG.warn("Sign-in: auth-service answered success with no session in it");
            throw new SignInFailedException(SignInFailure.AUTH_UNAVAILABLE);
        }
        if (response.isTwoFactorRequired()) {
            throw new SignInFailedException(SignInFailure.TWO_FACTOR_REQUIRED);
        }

        User principal = new User();
        principal.setId(response.getUserId());
        principal.setEmail(response.getEmail());
        principal.setFirstName(response.getFirstName());
        principal.setLastName(response.getLastName());
        principal.setUserType(response.getUserType());
        // B2B P0.5: the module of the tenant this session is scoped to — ModuleRouter prefers it over
        // userType so a multi-module user lands on the org they are actually working in.
        principal.setActiveOrgType(response.getActiveOrgType());
        principal.setEnabled(true);
        principal.setDemo(response.isDemo());

        List<GrantedAuthority> authorities = new ArrayList<>();
        if (response.getPrivileges() != null) {
            response.getPrivileges().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        if (response.getRoles() != null) {
            response.getRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        }

        // Stash the JWT server-side for downstream gateway calls (Phase 3).
        tokenStore.setAccessToken(response.getAccessToken());
        tokenStore.setRefreshToken(response.getRefreshToken());

        return new UsernamePasswordAuthenticationToken(principal, password, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }

    /** The {@code reason} code of auth-service's refusal envelope; null when the body is not one. */
    private static String refusalCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode reason = JSON.readTree(body).get("reason");
            return reason == null || reason.isNull() ? null : reason.asText();
        } catch (Exception notJson) {
            return null;
        }
    }
}
