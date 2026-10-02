package com.myplus.expense.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.myplus.common.security.HeaderAuthFilter;

import lombok.RequiredArgsConstructor;

/**
 * Stateless security: identity comes from the gateway through {@link HeaderAuthFilter}; CSRF and sessions off.
 * Without this the service falls back to Spring Boot's default chain and 401s every call (the audit-service
 * lesson). Method-level {@code @PreAuthorize} carries the owner/admin gates.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final HeaderAuthFilter headerAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
