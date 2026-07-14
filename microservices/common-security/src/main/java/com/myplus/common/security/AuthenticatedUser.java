package com.myplus.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The caller identity propagated by the gateway (via X-User-* / X-Org-Id / X-Location-* headers) and
 * rebuilt by {@link HeaderAuthFilter}. Shared across all servlet services so the type is identical
 * everywhere a controller reads {@code authentication.getPrincipal()}.
 */
@Data
@AllArgsConstructor
public class AuthenticatedUser {
    private Long userId;
    private String email;
    private List<SimpleGrantedAuthority> authorities;
    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). May be null. */
    private Long organizationId;

    // ── Multi-location (Stores/Branches). All null/empty until locations + grants exist (P2+), in which
    //    case a service treats the request as single-location and behaves exactly as before. ──────────
    /** The active store/branch for this request (gateway X-Location-Id). Null = single-location/unset. */
    private Long activeLocationId;
    /** Every store/branch this caller may access in the active org (gateway X-Location-Ids). Empty = unset. */
    private Set<Long> accessibleLocationIds;
    /** The caller's role at the active location: OWNER | ADMIN | USER (gateway X-Loc-Role). May be null. */
    private String roleAtLocation;

    /**
     * Legacy constructor (pre multi-location). Keeps existing call sites working; the location fields
     * default to unset, so behaviour is unchanged until the gateway stamps X-Location-* headers.
     */
    public AuthenticatedUser(Long userId, String email, List<SimpleGrantedAuthority> authorities, Long organizationId) {
        this(userId, email, authorities, organizationId, null, Collections.emptySet(), null);
    }
}
