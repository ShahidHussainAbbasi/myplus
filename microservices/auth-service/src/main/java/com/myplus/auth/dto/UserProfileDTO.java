package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private boolean enabled;
    private boolean accountNonLocked;
    private boolean twoFactorEnabled;
    private String userType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Set<String> roles;
}
