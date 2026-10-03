package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/** MKT-0a — the access rules, against a real security context (no mocks between the rule and the principal). */
class SellerAccessTest {

    private final SellerAccess access = new SellerAccess();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    static void as(Long org, Set<String> caps, String... authorities) {
        AuthenticatedUser u = new AuthenticatedUser(11L, "x@y", java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList(), org);
        u.setCapabilities(caps);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    @DisplayName("[MKT-R20.0] the capability check fails CLOSED: unresolved (null) capabilities are refused")
    void failsClosed() {
        as(7L, null, "ROLE_OWNER");
        assertThat(access.capabilityOn()).isFalse();
        assertThatThrownBy(access::assertCapabilityOn).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Settings → Configuration");
        as(7L, Set.of("expenseManagement"), "ROLE_OWNER");
        assertThat(access.capabilityOn()).as("another module's switch is not this one").isFalse();
        as(7L, Set.of("marketplaceSelling"), "ROLE_OWNER");
        assertThatCode(access::assertCapabilityOn).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R22.1] a user-tier member cannot accept agreements; owner and admin can")
    void tiers() {
        as(7L, Set.of("marketplaceSelling"), "WRITE_PRIVILEGE", "LOGIN_PRIVILEGE");
        assertThatThrownBy(access::assertOwnerOrAdmin).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Only the owner or an admin");
        as(7L, Set.of("marketplaceSelling"), "ADMIN_PRIVILEGE");
        assertThatCode(access::assertOwnerOrAdmin).doesNotThrowAnyException();
        as(7L, Set.of("marketplaceSelling"), "ROLE_OWNER");
        assertThatCode(access::assertOwnerOrAdmin).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R22.1] operator actions key on ROLE_ADMIN — a tenant owner's ADMIN_PRIVILEGE is not enough")
    void operatorIsRoleAdmin() {
        as(7L, Set.of("marketplaceSelling"), "ROLE_OWNER", "ADMIN_PRIVILEGE", "SUPER_PRIVILEGE");
        assertThatThrownBy(access::assertOperator).isInstanceOf(AccessDeniedException.class);
        as(1L, Set.of(), "ROLE_ADMIN");
        assertThatCode(access::assertOperator).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[MKT-R22.1] the org comes from the token; with none, every path refuses")
    void orgFromToken() {
        as(42L, Set.of(), "ROLE_OWNER");
        assertThat(access.org()).isEqualTo(42L);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(access::org).isInstanceOf(ValidationException.class);
    }

    @SuppressWarnings("unused")
    private static final List<String> NOTE = List.of();
}
