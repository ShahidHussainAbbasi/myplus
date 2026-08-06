package com.myplus.common.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Slice 3.1b — <b>the deny rule for portal principals.</b>
 * Design: microservices/docs/slices/edu-3.1b-portal-sign-in.md (D2)
 *
 * <h3>The problem this exists for</h3>
 *
 * Education's <b>read</b> endpoints are not privilege-gated — {@code getUserStudent},
 * {@code getUserGuardian}, {@code getMarksSheet} and {@code getUserFc} carry no {@code @PreAuthorize}.
 * That is correct while <b>every authenticated user is staff</b>, which was true until portal sign-in.
 *
 * <p>The moment a guardian holds a session, every one of those reads answers: the whole school's students,
 * marks, fee records and other families' contact details. No attack is required — the endpoint simply
 * replies. <b>Account creation is the small half of portal sign-in; this filter is the other half.</b>
 *
 * <h3>Deny by default, everywhere</h3>
 *
 * A portal principal may reach <b>only</b> paths this service explicitly allowlists
 * ({@code myplus.portal.allowlist}). <b>A service that declares no allowlist denies portal principals
 * entirely</b> — so all thirteen services fail closed, including ones written later by someone who has
 * never heard of the portal. Education declares exactly one entry: {@code /portal/**}.
 *
 * <p>This is the direct answer to the education review's <b>finding A</b>, which proved that a scoping rule
 * requiring every controller to remember it is forgotten in seven of them. The alternative — annotating all
 * 74 read endpoints — is 74 chances to be wrong, must be reapplied to every read added afterwards, and
 * <b>fails open</b> when someone forgets. This fails closed.
 *
 * <h3>Why it reads the HEADER rather than the SecurityContext</h3>
 *
 * {@link HeaderAuthFilter} populates the {@code SecurityContext}, but it is installed <i>inside</i> each
 * service's Spring Security chain, while an auto-registered {@code Filter} bean runs in the servlet chain
 * <i>before</i> it. Depending on the principal would therefore make this filter's correctness depend on
 * registration order in thirteen separate {@code SecurityConfig} classes — exactly the "remember it
 * everywhere" failure mode above.
 *
 * <p>Reading {@code X-User-Roles} directly makes it self-contained and order-independent, and it is safe
 * <b>in the deny direction</b>: forging the portal role only restricts the forger, and a caller who omits
 * it still cannot authenticate, because {@code HeaderAuthFilter} refuses identity headers that do not carry
 * the gateway's {@code X-Internal-Secret}. This filter can only ever take access away.
 *
 * <h3>Refusals are 404</h3>
 *
 * Never 403 — consistent with slice 3.1, where a guardian asking about another family's child is told
 * NOT_FOUND because "that exists, but not for you" is itself a disclosure. A 403 here and a 404 inside the
 * portal would let a prober map the surface by watching which refusal came back.
 */
public class PortalScopeFilter extends OncePerRequestFilter {

    /** The role that marks a session as portal-scoped. Granted alongside LOGIN_PRIVILEGE, never instead. */
    public static final String PORTAL_ROLE = "ROLE_PORTAL";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> allowlist;

    public PortalScopeFilter(List<String> allowlist) {
        this.allowlist = allowlist == null ? Collections.emptyList() : List.copyOf(allowlist);
    }

    /** Parses the comma-separated {@code myplus.portal.allowlist} property. Blank ⇒ deny everything. */
    public static List<String> parseAllowlist(String csv) {
        List<String> out = new ArrayList<>();
        if (StringUtils.hasText(csv)) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isPortalPrincipal(request.getHeader("X-User-Roles")) && !isAllowed(pathOf(request))) {
            // 404, and deliberately no body: a portal caller learns nothing about what else exists here.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * PURE. Is this roles header a portal session?
     *
     * <p>Exact, case-sensitive token match after splitting — deliberately not {@code contains()}, which
     * would also match a hypothetical {@code ROLE_PORTAL_ADMIN} and hand a staff role the portal's
     * restrictions (or, read the other way, let a near-miss name escape them).
     */
    public static boolean isPortalPrincipal(String rolesHeader) {
        if (!StringUtils.hasText(rolesHeader)) return false;
        for (String r : rolesHeader.split(",")) {
            if (PORTAL_ROLE.equals(r.trim())) return true;
        }
        return false;
    }

    /**
     * PURE. Is this path allowlisted?
     *
     * <p>The path is cleaned FIRST: {@code /portal/../getUserStudent} resolves to {@code /getUserStudent}
     * and is refused. Matching before normalising is the classic way an allowlist is walked straight past.
     */
    public static boolean allowed(String path, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) return false;   // fail closed
        if (path == null) return false;
        String clean = StringUtils.cleanPath(path);
        if (!clean.startsWith("/")) clean = "/" + clean;
        for (String pattern : allowlist) {
            if (MATCHER.match(pattern, clean)) return true;
        }
        return false;
    }

    private boolean isAllowed(String path) {
        return allowed(path, allowlist);
    }

    private static String pathOf(HttpServletRequest request) {
        String p = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && p != null && p.startsWith(ctx)) {
            p = p.substring(ctx.length());
        }
        return p;
    }
}
