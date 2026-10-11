package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;

/**
 * HMS H4 — a prescription routed to a branch is seen by that branch's staff, the owner, and nobody else; a
 * prescription with no branch is everyone's; a member with no grants (a one-branch business) is unconstrained.
 */
class StoreScopeTest {

    private final StoreScope scope = new StoreScope();

    private static void signIn(Set<Long> stores, String... authorities) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        AuthenticatedUser u = new AuthenticatedUser(5L, "u@x.pk", auths, 1L);
        u.setAccessibleLocationIds(stores);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auths));
    }

    @AfterEach
    void out() { SecurityContextHolder.clearContext(); }

    @Test
    void branch_staff_see_their_branch_and_unrouted_prescriptions_only() {
        signIn(Set.of(20L), "ROLE_BUSINESS_USER");
        assertThat(scope.seesAll()).isFalse();
        assertThat(scope.stores()).containsExactly(20L);
        assertThat(scope.canAccess(20L)).isTrue();
        assertThat(scope.canAccess(null)).as("unrouted: every branch").isTrue();
        assertThat(scope.canAccess(21L)).as("another branch's prescription").isFalse();
    }

    @Test
    void the_owner_sees_every_branch_even_with_grants() {
        signIn(Set.of(20L), "ROLE_OWNER");
        assertThat(scope.seesAll()).isTrue();
        assertThat(scope.canAccess(21L)).isTrue();
    }

    @Test
    void no_grants_means_a_one_branch_business_unchanged() {
        signIn(Set.of(), "ROLE_BUSINESS_USER");
        assertThat(scope.seesAll()).isTrue();
        assertThat(scope.stores()).containsExactly(-1L);   // the placeholder: "all" is passed separately
    }
}
