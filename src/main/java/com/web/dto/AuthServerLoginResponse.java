package com.web.dto;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code data} payload returned by auth-service {@code POST /api/auth/login} (and refresh).
 * Mirrors {@code com.myplus.auth.dto.AuthResponse}. Privileges are included (Model A) so the
 * monolith can rebuild its authority set without parsing the JWT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthServerLoginResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;
    private String userType;
    private Set<String> roles;
    private Set<String> privileges;
    private boolean twoFactorRequired;
    private boolean demo;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    public Set<String> getPrivileges() { return privileges; }
    public void setPrivileges(Set<String> privileges) { this.privileges = privileges; }

    public boolean isTwoFactorRequired() { return twoFactorRequired; }
    public void setTwoFactorRequired(boolean twoFactorRequired) { this.twoFactorRequired = twoFactorRequired; }

    public boolean isDemo() { return demo; }
    public void setDemo(boolean demo) { this.demo = demo; }
}
