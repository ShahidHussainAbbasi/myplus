package com.myplus.auth.security;

import com.myplus.auth.service.JwtService;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            String email = jwtService.extractUsername(token);
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                if (jwtService.validateToken(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    /*
                     * C3c — also publish the identity in the shape CurrentUser reads.
                     *
                     * Every other service is fronted by the gateway, which stamps X-User-* / X-Org-Id headers
                     * that HeaderAuthFilter turns into an AuthenticatedUser. auth-service is not: it validates
                     * the Bearer token itself and sets a UserDetails principal, so CurrentUser.organizationId()
                     * has always been null here. OrgUserController works around that by re-reading activeOrgId
                     * from the token by hand.
                     *
                     * That workaround does not scale to code auth-service now SHARES with other services: the
                     * common-settings SettingsController resolves the tenant through CurrentUser, so without
                     * this it would write every capability row against a null organization — silently, since
                     * nothing throws on a null org.
                     *
                     * Set as a REQUEST ATTRIBUTE rather than replacing the principal: CurrentUser.get() falls
                     * back to it, while every existing call site that expects a UserDetails principal keeps
                     * working unchanged.
                     */
                    Long activeOrgId = toLong(jwtService.extractClaim(token, c -> c.get("activeOrgId")));
                    Long uid = toLong(jwtService.extractClaim(token, c -> c.get("userId")));
                    List<SimpleGrantedAuthority> authorities = userDetails.getAuthorities().stream()
                            .map(a -> new SimpleGrantedAuthority(a.getAuthority()))
                            .collect(java.util.stream.Collectors.toList());
                    request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE,
                            new AuthenticatedUser(uid, email, authorities, activeOrgId));
                }
            }
        } catch (Exception ex) {
            // Token invalid - continue without auth, security will reject if needed
        }
        filterChain.doFilter(request, response);
    }

    /**
     * JWT numeric claims arrive as Integer or Long depending on magnitude, and as String from some issuers.
     * Same coercion {@code OrgUserController} already does by hand for {@code activeOrgId}.
     */
    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
