package com.myplus.market.service;

import java.util.List;
import java.util.Set;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;

/** Puts a caller in the security context, the way HeaderAuthFilter does for a real request. */
final class TestIdentity {

    static final Long PLATFORM_ORG = 1L;
    static final Long SELLER_ORG = 13L;
    static final Long OTHER_SELLER_ORG = 24L;

    private TestIdentity() {}

    static void operator() {
        set(new AuthenticatedUser(1L, "admin@myplus.com",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("LOGIN_PRIVILEGE")),
                PLATFORM_ORG));
    }

    static void sellerOwner(Long org, Set<String> caps) {
        AuthenticatedUser u = new AuthenticatedUser(70L + org, "owner." + org + "@shop.com",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"), new SimpleGrantedAuthority("ADMIN_PRIVILEGE"),
                        new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), org);
        u.setCapabilities(caps);
        set(u);
    }

    static void clear() { SecurityContextHolder.clearContext(); }

    private static void set(AuthenticatedUser u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }
}
