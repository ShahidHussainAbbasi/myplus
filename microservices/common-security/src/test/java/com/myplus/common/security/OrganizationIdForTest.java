package com.myplus.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E5b — an operator naming another business is REFUSED, never answered about their own.
 *
 * <p>Since E5 the fallback for an operator without a support session was the tenant anti-IDOR rule: silently use
 * the caller's own org. On the console that printed the platform org's trail, counts and money under another
 * business's name — and turned "Clear flags" into a write on the operator's own catalogue.
 */
class OrganizationIdForTest {

    private static final long OPERATOR_ORG = 8L;
    private static final long TENANT_ORG = 13L;
    private static final long OTHER = 49L;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static void as(AuthenticatedUser u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private static AuthenticatedUser operator() {
        return new AuthenticatedUser(1L, "admin@myplus.com",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), OPERATOR_ORG);
    }

    private static AuthenticatedUser tenant() {
        return new AuthenticatedUser(7L, "owner@shop.com",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"), new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), TENANT_ORG);
    }

    @Test
    void an_operator_naming_another_business_WITHOUT_a_session_is_refused_not_answered_about_their_own() {
        as(operator());
        assertThatThrownBy(() -> CurrentUser.organizationIdFor(OTHER))
                .isInstanceOf(SupportSessionRequiredException.class)
                .isInstanceOf(AccessDeniedException.class)             // still a 403 to everything that handles one
                .hasMessageContaining("support session");
    }

    @Test
    void with_an_open_session_the_operator_is_answered_about_THAT_business() {
        AuthenticatedUser op = operator();
        op.setSupportOrgId(OTHER);
        op.setSupportUntil(LocalDateTime.now().plusMinutes(10));
        as(op);
        assertThat(CurrentUser.organizationIdFor(OTHER)).isEqualTo(OTHER);
    }

    @Test
    void an_EXPIRED_session_is_refused_too() {
        AuthenticatedUser op = operator();
        op.setSupportOrgId(OTHER);
        op.setSupportUntil(LocalDateTime.now().minusMinutes(1));
        as(op);
        assertThatThrownBy(() -> CurrentUser.organizationIdFor(OTHER)).isInstanceOf(SupportSessionRequiredException.class);
    }

    @Test
    void a_session_for_one_business_does_not_open_another() {
        AuthenticatedUser op = operator();
        op.setSupportOrgId(OTHER);
        op.setSupportUntil(LocalDateTime.now().plusMinutes(10));
        as(op);
        assertThatThrownBy(() -> CurrentUser.organizationIdFor(TENANT_ORG)).isInstanceOf(SupportSessionRequiredException.class);
    }

    @Test
    void an_operator_asking_about_their_OWN_org_or_none_gets_their_own() {
        as(operator());
        assertThat(CurrentUser.organizationIdFor(OPERATOR_ORG)).isEqualTo(OPERATOR_ORG);
        assertThat(CurrentUser.organizationIdFor(null)).isEqualTo(OPERATOR_ORG);
    }

    @Test
    void a_TENANT_naming_another_business_is_still_quietly_ignored__anti_IDOR_unchanged() {
        as(tenant());
        assertThat(CurrentUser.organizationIdFor(OTHER)).isEqualTo(TENANT_ORG);
        assertThat(CurrentUser.organizationIdFor(null)).isEqualTo(TENANT_ORG);
    }
}
