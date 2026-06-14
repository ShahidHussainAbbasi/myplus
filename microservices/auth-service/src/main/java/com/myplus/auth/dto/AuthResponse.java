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
    private Set<String> roles;
    // Flattened privileges (Model A) so privilege-based clients (the monolith) can rebuild
    // their authority set directly from the login response without parsing the JWT.
    private Set<String> privileges;
    private boolean twoFactorRequired;
    // Free-trial demo session: the front-end shows the demo banner + upsell (gateway caps writes at 50/module).
    private boolean demo;
}
