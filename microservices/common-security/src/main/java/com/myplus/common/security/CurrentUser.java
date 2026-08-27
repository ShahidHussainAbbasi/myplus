package com.myplus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Single, shared accessor for the caller identity that {@link HeaderAuthFilter} placed in the security
 * context (slice 33, Phase 4.5). Replaces the per-service {@code RequestUtil.getCurrentUser()} copies
 * (which had diverged 4×) with one source of truth every service can use for org-scoping.
 */
public final class CurrentUser {

    /** Request attribute {@link HeaderAuthFilter} mirrors the principal into; used as the fallback below. */
    public static final String REQUEST_ATTRIBUTE = "_authenticated_user";

    private CurrentUser() {}

    /** The authenticated caller, if the gateway propagated an identity for this request. */
    public static Optional<AuthenticatedUser> get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        // Fallback: HeaderAuthFilter also mirrors the principal into a request attribute, which survives
        // even if the security context was cleared mid-request. Preserves the old RequestUtil behaviour.
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra
                && sra.getRequest().getAttribute(REQUEST_ATTRIBUTE) instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** Active tenant for this request (gateway X-Org-Id), or {@code null} when unauthenticated/no org. */
    public static Long organizationId() {
        return get().map(AuthenticatedUser::getOrganizationId).orElse(null);
    }

    /**
     * C3c — the tenant's enabled capabilities as resolved when the token was minted, or {@code null} when
     * they were not resolved for this request.
     *
     * <p><b>{@code null} and an empty set mean opposite things.</b> null = unresolved, so a caller must fall
     * back to its own settings store (pre-C3c behaviour). Empty = resolved and nothing is enabled. Anything
     * treating "no capabilities" as one case will either blank every screen for tenants on older tokens or
     * quietly permit everything for an all-off tenant.
     *
     * <p>Read {@code CapabilityService.isEnabledFor} rather than this directly: it applies the fallback and
     * the shape preset. This accessor exists for the resolver and for tests that need the raw answer.
     */
    public static java.util.Set<String> capabilities() {
        return get().map(AuthenticatedUser::getCapabilities).orElse(null);
    }

    /** Caller user id (audit + NULL-fallback scoping), or {@code null} when unauthenticated. */
    public static Long userId() {
        return get().map(AuthenticatedUser::getUserId).orElse(null);
    }

    /**
     * Caller email, or {@code null} when unauthenticated.
     *
     * <p>For <b>stamping an audit record at write time</b>, which is the only reason a service should want a
     * name rather than an id. An audit trail must still be readable after the person has left and their user
     * row is gone, so the name is written with the record instead of resolved when it is read — the rule
     * {@code CustomerHistory.bookedByName} already follows.
     */
    public static String email() {
        return get().map(AuthenticatedUser::getEmail).orElse(null);
    }

    /** The caller, or fail fast — for write paths that must be attributable to a tenant/user. */
    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated user in security context"));
    }
}
