package com.myplus.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Slice 33, Phase 2b — locks down the single identity accessor the four domain services now share
 * (via their thin RequestUtil). Covers both the SecurityContext path and the request-attribute fallback
 * that {@link HeaderAuthFilter} mirrors into, so consolidation didn't drop the old RequestUtil behaviour.
 */
class CurrentUserTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(7L, "u@x.com",
                List.of(new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), 42L);
    }

    @Test
    void empty_when_nothing_authenticated() {
        assertThat(CurrentUser.get()).isEmpty();
        assertThat(CurrentUser.organizationId()).isNull();
        assertThat(CurrentUser.userId()).isNull();
    }

    @Test
    void reads_principal_from_security_context() {
        AuthenticatedUser u = user();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));

        assertThat(CurrentUser.get()).contains(u);
        assertThat(CurrentUser.organizationId()).isEqualTo(42L);
        assertThat(CurrentUser.userId()).isEqualTo(7L);
        assertThat(CurrentUser.require()).isSameAs(u);
    }

    @Test
    void falls_back_to_request_attribute_when_context_is_empty() {
        AuthenticatedUser u = user();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, u);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // No authentication in the security context — only the mirrored request attribute is present.
        assertThat(CurrentUser.get()).contains(u);
        assertThat(CurrentUser.organizationId()).isEqualTo(42L);
    }
}
