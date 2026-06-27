package com.myplus.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Establishes the Spring Security context from identity headers stamped by the API gateway
 * after it validated the caller's JWT. Trusts the headers only when the configured internal
 * secret matches, so a request reaching a service directly (bypassing the gateway) cannot
 * forge an identity.
 *
 * <p>Registered automatically for every servlet service via {@link CommonSecurityAutoConfiguration};
 * services do not declare their own copy.
 */
public class HeaderAuthFilter extends OncePerRequestFilter {

    // Must match the gateway's gateway.internal-secret. Empty = not enforced (legacy/dev).
    @Value("${service.internal-secret:}")
    private String internalSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // When a secret is configured, only trust identity headers stamped by the gateway.
        if (internalSecret != null && !internalSecret.isEmpty()
                && !internalSecret.equals(request.getHeader("X-Internal-Secret"))) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = request.getHeader("X-User-Id");
        String email = request.getHeader("X-User-Email");
        String rolesHeader = request.getHeader("X-User-Roles");
        String privilegesHeader = request.getHeader("X-User-Privileges");
        String orgIdHeader = request.getHeader("X-Org-Id");

        if (userId != null && !userId.isBlank() && !"null".equals(userId)) {
            Set<SimpleGrantedAuthority> deduped = new LinkedHashSet<>();
            deduped.addAll(parseAuthorities(rolesHeader));
            deduped.addAll(parseAuthorities(privilegesHeader));
            List<SimpleGrantedAuthority> authorities = new ArrayList<>(deduped);
            try {
                Long organizationId = parseLongOrNull(orgIdHeader);
                AuthenticatedUser principal = new AuthenticatedUser(Long.valueOf(userId), email, authorities, organizationId);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                // Also store in request attributes as a reliable fallback (read by CurrentUser.get()).
                request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, principal);
            } catch (NumberFormatException ignored) {
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Parse a numeric header into a Long, or null when absent/blank/non-numeric. */
    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Parse a comma-separated header (tolerating [ ] and quotes) into granted authorities. */
    private List<SimpleGrantedAuthority> parseAuthorities(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
    }
}
