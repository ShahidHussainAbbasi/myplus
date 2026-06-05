package com.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import com.web.util.AuthServerClient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * On logout, revoke the JWT at the auth-service (server mode) and clear the session-scoped
 * {@link TokenStore}. Runs before the session is cleared, so the token is still available.
 * Best-effort: a failure to reach the auth-service never blocks local logout.
 */
@Component
public class RevokeTokenLogoutHandler implements LogoutHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RevokeTokenLogoutHandler.class);

    private final AuthServerClient authServerClient;
    private final TokenStore tokenStore;

    public RevokeTokenLogoutHandler(AuthServerClient authServerClient, TokenStore tokenStore) {
        this.authServerClient = authServerClient;
        this.tokenStore = tokenStore;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            if (tokenStore.hasAccessToken()) {
                authServerClient.logout(tokenStore.getAccessToken());
            }
        } catch (Exception e) {
            LOGGER.warn("Auth-server logout/revocation failed (continuing local logout): {}", e.getMessage());
        } finally {
            tokenStore.clear();
        }
    }
}
