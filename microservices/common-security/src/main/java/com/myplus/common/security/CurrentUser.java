package com.myplus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Single, shared accessor for the caller identity that {@link HeaderAuthFilter} placed in the security
 * context (slice 33, Phase 4.5). Replaces the per-service {@code RequestUtil.getCurrentUser()} copies
 * (which had diverged 4×) with one source of truth every service can use for org-scoping.
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** The authenticated caller, if the gateway propagated an identity for this request. */
    public static Optional<AuthenticatedUser> get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** Active tenant for this request (gateway X-Org-Id), or {@code null} when unauthenticated/no org. */
    public static Long organizationId() {
        return get().map(AuthenticatedUser::getOrganizationId).orElse(null);
    }

    /** Caller user id (audit + NULL-fallback scoping), or {@code null} when unauthenticated. */
    public static Long userId() {
        return get().map(AuthenticatedUser::getUserId).orElse(null);
    }

    /** The caller, or fail fast — for write paths that must be attributable to a tenant/user. */
    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated user in security context"));
    }
}
