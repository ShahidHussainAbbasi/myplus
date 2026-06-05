package com.security;

import java.io.Serializable;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Holds the auth-service JWT (access + refresh) for the current HTTP session. Kept server-side
 * only — the browser never sees the tokens, just the JSESSIONID cookie — so XSS cannot steal them.
 * Populated at login by {@link AuthServerAuthenticationProvider}; read by downstream REST clients
 * to attach {@code Authorization: Bearer ...} when calling the gateway (Phase 3).
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_SESSION, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class TokenStore implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;
    private String refreshToken;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isEmpty();
    }

    public void clear() {
        this.accessToken = null;
        this.refreshToken = null;
    }
}
