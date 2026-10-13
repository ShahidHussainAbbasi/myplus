package com.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.*;

/**
 * PERM-1 — the enforcement half. Without this the matrix is decoration.
 *
 * <p>Design: {@code microservices/docs/slices/perm-1-permission-sets-design.md}
 *
 * <h3>Why a path map and not 198 annotations</h3>
 * business-service alone has 198 endpoints and 27 of them carry {@code @PreAuthorize}. Annotating the
 * other 171 by hand is where this feature would quietly die: it is a week of mechanical edits across
 * services, every one of which can be forgotten, and nothing fails when one is. One ordered map is a
 * single artefact a person can read top to bottom and audit against the matrix screen.
 *
 * <h3>⚠ UNMAPPED IS ALLOWED, AND LOGGED — a deliberate, temporary hole</h3>
 * Deny-by-default on an incomplete map means the product answers 403 to screens that worked yesterday,
 * on the morning it deploys. So an unmapped path passes and is logged at DEBUG as {@code perm.unmapped},
 * and each phase moves paths into the map. This is written down here rather than discovered later:
 * <b>until a path appears below, its area's permissions do not restrict it.</b>
 *
 * <h3>What this cannot do</h3>
 * It covers what the BROWSER does, because the browser talks to the monolith. A member who extracted
 * their own token could call the gateway directly and bypass it. That is a real gap, and closing it is
 * putting the same rule in {@code common-security} so each service enforces for itself — phase 2. It is
 * named here so nobody reads this class as the last word.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionInterceptor.class);

    /** One rule: a method, a path prefix, the permission it demands, and that permission's catalogue label. */
    public record Rule(String method, String path, String permission, String label) {
        boolean matches(String m, String p) {
            return ("*".equals(method) || method.equalsIgnoreCase(m)) && p.startsWith(path);
        }
    }

    /**
     * RW-0c (2026-10-12) — where the rules come from: auth-service's {@code endpoint_permission} rows (V26), most
     * specific first, via {@link EndpointRuleCatalog}. Null = not loaded yet. Never a list in this class.
     */
    public interface RuleSource {
        java.util.List<Rule> rules();
    }

    private final RuleSource source;

    public PermissionInterceptor(RuleSource source) { this.source = source; }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /*
     * RW-0c: the 23 rules that lived here (MOST SPECIFIC FIRST — the first match wins; /addProductStock before
     * /addProduct) are now the endpoint_permission rows (auth V26), in the same order. Reads stay separated from
     * writes there: a member who may see the sale screen is not thereby a member who may ring one up.
     */

    /**
     * Paths every signed-in member reaches whatever their set says.
     *
     * <p>Their own password, the shared pickers a sale cannot be composed without, and the settings read
     * the dashboard makes on load. Blocking these produces a member who can technically sell and
     * practically cannot — an empty item picker with no error, which is the failure this whole design
     * has been steering around.
     */
    private static final List<String> ALWAYS = List.of(
            "/user/", "/login", "/logout", "/js/", "/css/", "/images/", "/webjars/",
            "/getBusinessDashboardStats", "/catalogProductPicker", "/customerOptions",
            /*
             * ⚠ READING THE TENANT'S CONFIG IS NOT "SEEING THE SETTINGS SCREEN", and gating it as though
             * it were broke every member's till.
             *
             * loadPosFeatureFlags() calls this on EVERY page load to learn how this shop's screens are
             * configured — barcode on or off, which fields the sale line shows, whether the keyboard flow
             * is enabled, how many instalments to seed. Those flags fail CLOSED by design, so a 403 here
             * did not produce an error: it produced a till quietly running on defaults, with the shop's
             * own configuration silently absent. Reported as "the settings were not the same as the
             * owner's" — and they were the same, they just never arrived.
             *
             * The distinction that matters: READING the configuration to render your own screen is not
             * the same act as OPENING Settings to change it. The screen is hidden by sec:authorize in the
             * template, and the CHANGE is gated above by settings.edit. That is where the control belongs.
             */
            "/getBusinessConfig"
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws Exception {
        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());

        for (String open : ALWAYS) if (path.startsWith(open)) return true;

        java.util.List<Rule> rules = source.rules();
        if (rules == null) {
            /*
             * RW-0c option (a), the client's choice: the rules are not loaded yet (auth-service unreachable and no
             * copy). A READ passes — opening a screen changes nothing. A WRITE waits: a sale, a purchase or a stock
             * change must never go through unchecked (STANDARDS: never optimistic about money). It says so plainly
             * and saves nothing; the next attempt loads the rules.
             */
            if (isRead(req.getMethod())) return true;
            LOGGER.warn("perm.rulesNotLoaded {} {} — write held until the endpoint rules load", req.getMethod(), path);
            write(res, 503, "Permissions are still loading — please try again in a moment. Nothing was saved.");
            return false;
        }
        Rule rule = null;
        for (Rule r : rules) {
            if (r.matches(req.getMethod(), path)) { rule = r; break; }
        }
        if (rule == null) {
            // The temporary hole, made visible. Grep `perm.unmapped` to see what phase 2 must map.
            LOGGER.debug("perm.unmapped {} {}", req.getMethod(), path);
            return true;
        }

        if (holds(rule.permission())) return true;

        /*
         * THE REFUSAL NAMES THE THING, and says who can fix it.
         *
         * "Access denied" tells a cashier nothing they can act on — they cannot tell a permission from a
         * bug, so they phone somebody. "You are not allowed to record purchases. Ask the shop owner."
         * ends the question at the counter, which is the only place it matters.
         */
        LOGGER.info("perm.refused user={} {} {} needs={}", username(), req.getMethod(), path,
                rule.permission());
        // RW-0c: the action's name is the catalogue's label (permission.label), never a sentence written here
        write(res, HttpServletResponse.SC_FORBIDDEN, "Your role does not include \u201c" + rule.label()
                + "\u201d. Ask the shop owner to change your permission set on Team.");
        return false;
    }

    private static boolean isRead(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    private static void write(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "FAILED");
        body.put("success", false);
        body.put("message", message);
        res.getWriter().write(JSON.writeValueAsString(body));
    }

    /** Does the signed-in member hold this permission? Read straight off the token's authorities. */
    private boolean holds(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String s = a.getAuthority();
            if (permission.equals(s)) return true;
            // An OWNER holds everything without a row anywhere saying so (design G-4), and the platform
            // roles keep the reach they already had.
            if ("ROLE_OWNER".equals(s) || "SUPER_PRIVILEGE".equals(s)) return true;
        }
        return false;
    }

    private String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "?" : auth.getName();
    }
}
