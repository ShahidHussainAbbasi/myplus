package com.myplus.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The caller identity propagated by the gateway (via X-User-* headers) and rebuilt by
 * {@link HeaderAuthFilter}. Shared across all servlet services so the type is identical
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
}
