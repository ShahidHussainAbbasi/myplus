package com.myplus.business_service.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class HeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        String email = request.getHeader("X-User-Email");
        String rolesHeader = request.getHeader("X-User-Roles");

        if (userId != null && !userId.isBlank() && !"null".equals(userId)) {
            List<SimpleGrantedAuthority> authorities = (rolesHeader != null && !rolesHeader.isBlank())
                    ? Arrays.stream(rolesHeader.replaceAll("[\\[\\]\"]", "").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(SimpleGrantedAuthority::new).collect(Collectors.toList())
                    : List.of();
            try {
                AuthenticatedUser principal = new AuthenticatedUser(Long.valueOf(userId), email, authorities);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                // Also store in request attributes as a reliable fallback
                request.setAttribute("_authenticated_user", principal);
            } catch (NumberFormatException ignored) {
            }
        }
        filterChain.doFilter(request, response);
    }
}
