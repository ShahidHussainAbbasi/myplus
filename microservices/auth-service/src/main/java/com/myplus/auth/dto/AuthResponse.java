package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;
    // Drives dashboard routing in the monolith front-end (BUSINESS/EDUCATION/WELFARE/AGRICULTURE).
    private String userType;

    /**
     * B2B P0.5 — the MODULE of the tenant this token is scoped to (same vocabulary as {@link #userType}).
     * Clients route on this in preference to {@code userType}, so one login reaches every module it belongs
     * to. NULL for tenants whose {@code Organization.type} was never set; consumers fall back to userType.
     */
    private String activeOrgType;
    private Set<String> roles;
    // Flattened privileges (Model A) so privilege-based clients (the monolith) can rebuild
    // their authority set directly from the login response without parsing the JWT.
    private Set<String> privileges;
    private boolean twoFactorRequired;
    // Free-trial demo session: the front-end shows the demo banner + upsell (gateway caps writes at 50/module).
    private boolean demo;
}
