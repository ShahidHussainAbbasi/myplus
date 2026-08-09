package com.myplus.notification.security;

import com.myplus.common.security.HeaderAuthFilter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final HeaderAuthFilter headerAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Internal infrastructure API: callers may be in public/no-user contexts (demo leads, registration,
            // password reset), so the endpoint is permitAll at the service. External access is gated by the
            // gateway's JwtAuthenticationFilter on /api/notifications/**; prod adds the internal-secret.
            .authorizeHttpRequests(auth -> auth
                // Slice 105 — READS ARE NOT permitAll, and this rule must stay ABOVE the one below:
                // Spring Security takes the first match, so ordering IS the control here. Delivery history
                // is correspondence — who was told what, and when. The send path is open because callers
                // are often pre-auth (registration, password reset); reading back is not, and it is
                // additionally tenant-scoped in the controller.
                // The bare paths are listed alongside the /** forms deliberately: whether "/**" matches zero
                // segments varies with the matcher strategy in play, and a security rule must not depend on
                // that subtlety. If it silently stopped matching, the endpoint would fall through to
                // permitAll below — failing OPEN, exactly how the portal deny rule broke in 3.1b.
                .requestMatchers("/api/notifications/deliveries", "/api/notifications/deliveries/**",
                                 "/api/notifications/broadcasts", "/api/notifications/broadcasts/**")
                    .authenticated()
                .requestMatchers("/actuator/**", "/api/notifications/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
