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
                // Multi-location (Pattern A): active/accessible stores + role at the active location. All
                // absent => single-location, so the principal keeps its unset defaults and nothing changes.
                principal.setActiveLocationId(parseLongOrNull(request.getHeader("X-Location-Id")));
                principal.setAccessibleLocationIds(parseLongSet(request.getHeader("X-Location-Ids")));
                principal.setRoleAtLocation(cleanHeader(request.getHeader("X-Loc-Role")));
                // C3c: the tenant's capabilities as resolved at token mint. Absent header => stays null =>
                // the callee falls back to its own settings store (pre-C3c behaviour). See parseCapabilities.
                principal.setCapabilities(parseCapabilities(request.getHeader("X-Org-Caps")));
                // E5 — the support scope. Absent header => no session => the caller reaches only their own
                // organization, which is what every request that is not platform support should get.
                applySupportScope(principal, request.getHeader("X-Support-Scope"));
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                // Also store in request attributes as a reliable fallback (read by CurrentUser.get()).
                request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, principal);
            } catch (NumberFormatException ignored) {
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * E5 — parse {@code X-Support-Scope} ("orgId|expiresAt|writeApproved") onto the principal.
     *
     * <p><b>Anything unparseable leaves the principal with NO scope.</b> A malformed header must never widen
     * a caller: the failure mode of guessing would be a cross-tenant read granted by a typo, and there is no
     * plausible reading of a broken value that is safer than "no session".
     *
     * <p>The expiry is applied here rather than trusted blindly at the call site, so a token still carrying a
     * finished session grants nothing.
     */
    private void applySupportScope(AuthenticatedUser principal, String header) {
        String v = cleanHeader(header);
        if (v == null) return;
        String[] parts = v.split("\\|", -1);
        if (parts.length < 2) return;
        try {
            principal.setSupportOrgId(Long.valueOf(parts[0].trim()));
            principal.setSupportUntil(java.time.LocalDateTime.parse(parts[1].trim()));
            principal.setSupportWrite(parts.length > 2 && "true".equalsIgnoreCase(parts[2].trim()));
        } catch (RuntimeException malformed) {
            principal.setSupportOrgId(null);
            principal.setSupportUntil(null);
            principal.setSupportWrite(false);
        }
    }

    /** Trim a header; treat blank / literal "null" as absent. */
    private String cleanHeader(String v) {
        if (v == null) return null;
        String t = v.trim();
        return (t.isEmpty() || "null".equals(t)) ? null : t;
    }

    /** Parse a comma-separated list of ids (e.g. "3,7,12") into a Long set; empty when absent/blank. */
    /**
     * C3c — the {@code X-Org-Caps} header into a capability set.
     *
     * <p><b>Returns null for an absent header and an EMPTY SET for {@code "-"}, and the difference is the
     * whole point.</b> null means the capabilities were never resolved — an older token, or auth-service
     * unable to read its settings store — and the callee must fall back to its own store, which is exactly
     * the behaviour before C3c. An empty set means auth resolved them and this tenant has none enabled, which
     * is authoritative.
     *
     * <p>Collapsing the two would either blank every screen for every tenant still holding a pre-C3c token —
     * a self-inflicted outage on deploy day — or make a genuinely all-off tenant silently permissive. The
     * sentinel exists because an empty header value does not reliably survive HTTP transport, so "resolved,
     * nothing enabled" could not otherwise be expressed at all.
     *
     * <p>Trusting this header is safe only because the gateway strips any client-supplied copy before
     * stamping its own; see {@code JwtAuthenticationFilter}. Without that removal a caller could grant itself
     * any capability by naming it here.
     */
    private Set<String> parseCapabilities(String header) {
        if (header == null || header.isBlank()) return null;          // unresolved -> fall back
        String cleaned = header.replaceAll("[\\[\\]\"]", "").trim();
        Set<String> out = new LinkedHashSet<>();
        if ("-".equals(cleaned)) return out;                          // resolved, nothing enabled
        for (String part : cleaned.split(",")) {
            String code = part.trim();
            if (!code.isEmpty()) out.add(code);
        }
        return out;
    }

    private Set<Long> parseLongSet(String header) {
        Set<Long> out = new LinkedHashSet<>();
        if (header == null || header.isBlank()) return out;
        for (String part : header.replaceAll("[\\[\\]\"]", "").split(",")) {
            Long v = parseLongOrNull(part);
            if (v != null) out.add(v);
        }
        return out;
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
        // Delegated to AuthorityHeader so this format has exactly ONE parser. It used to live here, and
        // PortalScopeFilter's second, subtly different copy is what let a portal session read staff data.
        return AuthorityHeader.tokens(header).stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
    }
}
