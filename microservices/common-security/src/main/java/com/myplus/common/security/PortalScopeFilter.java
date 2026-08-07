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

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> allowlist;
    private final List<String> confinedRoles;

    public PortalScopeFilter(List<String> allowlist, List<String> confinedRoles) {
        this.allowlist = allowlist == null ? Collections.emptyList() : List.copyOf(allowlist);
        this.confinedRoles = confinedRoles == null ? Collections.emptyList() : List.copyOf(confinedRoles);
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
        if (isConfined(request.getHeader("X-User-Roles"), confinedRoles) && !isAllowed(pathOf(request))) {
            // 404, and deliberately no body: a portal caller learns nothing about what else exists here.
            //
            // setStatus + flushBuffer, NOT sendError — and the difference is the whole of D4.
            // sendError() asks the container to run its ERROR dispatch, which re-enters the filter chain
            // for /error. This filter short-circuits BEFORE the security chain, so that dispatch carries no
            // authentication, Spring Security refuses it, and the caller receives **403** — precisely the
            // status D4 exists to avoid, because it confirms the endpoint is real and merely forbidden.
            // Measured on 2026-08-06: with sendError, a confined session got 403 while a staff session got
            // 200 on the same URL. Committing the response ourselves keeps the refusal indistinguishable
            // from a route that does not exist.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            response.flushBuffer();
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * PURE. Does this roles header carry a CONFINED role?
     *
     * <p>The confined set is CONFIGURATION, not a hard-coded marker — currently {@code ROLE_GUARDIAN},
     * and 3.3 adds {@code ROLE_STUDENT}. Roles name <b>who someone is</b>, matching every other role on
     * this platform ({@code ROLE_EDUCATION_USER}, {@code ROLE_OWNER}, …); this property names <b>which of
     * them are restricted to a portal surface</b>. Identity and policy stay separate.
     *
     * <p><b>The cost of that choice, stated so it is not forgotten:</b> a new external audience is NOT
     * confined automatically — whoever adds one must add its role here. That is a "remember it" rule, the
     * failure mode finding A proved this codebase has. It is mitigated by keeping this property next to
     * {@code myplus.portal.allowlist} so both halves of the policy are read together, and by 3.3's design
     * doc carrying it as a checklist item. An empty set confines nobody, which is the correct default for
     * the twelve services that have no portal at all.
     *
     * <p>Exact, case-sensitive token match after splitting — deliberately not {@code contains()}, which
     * would also match a near-miss like {@code ROLE_GUARDIAN_ADMIN}.
     *
     * <p><b>Parsing is delegated to {@link AuthorityHeader}, and that is a FIX, not a tidy-up.</b> This
     * method used to do its own {@code split(",")}, which is wrong for the value the gateway actually
     * stamps: it renders the JWT claim with {@code List.toString()}, so the header reads
     * {@code [ROLE_GUARDIAN]}. Comparing that against {@code ROLE_GUARDIAN} never matched, and this filter
     * — the ONLY control standing between a portal session and ~74 ungated staff reads — <b>failed open</b>
     * whenever the call arrived through the gateway. It worked in the monolith's legacy direct mode, which
     * stamps the bare form, so the control was live in one path and absent in the other. See
     * {@link AuthorityHeader} for the rule this earned.
     */
    public static boolean isConfined(String rolesHeader, List<String> confinedRoles) {
        if (!StringUtils.hasText(rolesHeader) || confinedRoles == null || confinedRoles.isEmpty()) return false;
        for (String r : AuthorityHeader.tokens(rolesHeader)) {
            if (confinedRoles.contains(r)) return true;
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
