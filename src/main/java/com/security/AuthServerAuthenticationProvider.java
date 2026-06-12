package com.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
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
        if (authentication.getDetails() instanceof CustomWebAuthenticationDetails details) {
            verificationCode = details.getVerificationCode();
        }

        AuthServerLoginResponse response;
        try {
            response = authServerClient.login(email, password, verificationCode);
        } catch (HttpStatusCodeException e) {
            // auth-service returns 4xx for invalid credentials / locked account / wrong 2FA code.
            // If a code was supplied, the failure is most likely the code → give a 2FA-specific message.
            if (verificationCode != null && !verificationCode.isBlank()) {
                throw new BadCredentialsException("Invalid 2FA code");
            }
            throw new BadCredentialsException("Invalid username or password");
        } catch (RestClientException e) {
            throw new AuthenticationServiceException("Authentication server is unavailable", e);
        }

        if (response == null) {
            throw new BadCredentialsException("Invalid username or password");
        }
        if (response.isTwoFactorRequired()) {
            throw new BadCredentialsException("Verification code required");
        }

        User principal = new User();
        principal.setId(response.getUserId());
        principal.setEmail(response.getEmail());
        principal.setFirstName(response.getFirstName());
        principal.setLastName(response.getLastName());
        principal.setUserType(response.getUserType());
        principal.setEnabled(true);

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
}
