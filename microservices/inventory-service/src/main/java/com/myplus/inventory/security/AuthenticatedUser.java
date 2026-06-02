package com.myplus.inventory.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

@Data @AllArgsConstructor
public class AuthenticatedUser {
    private Long userId;
    private String email;
    private List<SimpleGrantedAuthority> authorities;
}
