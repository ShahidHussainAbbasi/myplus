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

    /** One rule: a method, a path prefix, and the permission it demands. */
    private record Rule(String method, String path, String permission) {
        boolean matches(String m, String p) {
            return ("*".equals(method) || method.equalsIgnoreCase(m)) && p.startsWith(path);
        }
    }

    /**
     * The map, MOST SPECIFIC FIRST — the first match wins.
     *
     * <p>Order is load-bearing: {@code /voidSell} must be tested before {@code /sell}, or voiding an
     * invoice would be allowed by whoever may merely see one. Keeping the specific rules above the
     * general ones is the whole discipline of this list.
     *
     * <p>Reads are separated from writes deliberately. A member who may see the sale screen is not
     * thereby a member who may ring one up, and collapsing GET and POST onto one permission would make
     * "view" mean "do" for every area at once.
     */
    private static final List<Rule> RULES = List.of(
            // ── the destructive and the sensitive, first ────────────────────────────────────────
            new Rule("POST", "/voidSell",              "sale.void"),
            new Rule("POST", "/deleteSell",            "sale.delete"),
            new Rule("POST", "/deletePurchase",        "purchase.delete"),
            new Rule("POST", "/deleteCustomer",        "customer.delete"),
            new Rule("POST", "/deleteProduct",         "product.delete"),
            new Rule("POST", "/deleteVender",          "supplier.delete"),

            // ── team: the owner's own example, "you are not authorized to create a user" ────────
            new Rule("POST",   "/team/permissions",    "team.edit"),
            new Rule("DELETE", "/team/permissions",    "team.edit"),
            new Rule("POST",   "/team/users",          "team.create"),
            new Rule("GET",    "/team/",               "team.view"),

            // ── opening balances: writes the general ledger ────────────────────────────────────
            new Rule("POST", "/reverseOpeningBalance", "opening.reverse"),
            new Rule("POST", "/postOpeningBalance",    "opening.create"),
            new Rule("GET",  "/openingBalanceSummary", "opening.view"),

            // ── settings ───────────────────────────────────────────────────────────────────────
            new Rule("POST", "/saveBusinessConfig",    "settings.edit"),
            new Rule("GET",  "/getBusinessConfig",     "settings.view"),

            // ── the two the owner asked for by name ────────────────────────────────────────────
            new Rule("POST", "/updateSell",            "sale.edit"),
            new Rule("POST", "/addSell",               "sale.create"),
            new Rule("POST", "/updatePurchase",        "purchase.edit"),
            new Rule("POST", "/addPurchase",           "purchase.create"),

            // ── the register ───────────────────────────────────────────────────────────────────
            new Rule("POST", "/addCustomer",           "customer.create"),
            new Rule("POST", "/addProduct",            "product.create"),
            new Rule("POST", "/addVender",             "supplier.create")
    );

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
            "/getBusinessDashboardStats", "/catalogProductPicker", "/customerOptions"
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws Exception {
        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());

        for (String open : ALWAYS) if (path.startsWith(open)) return true;

        Rule rule = null;
        for (Rule r : RULES) {
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
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"FAILED\",\"success\":false,\"message\":"
                + "\"You are not allowed to " + describe(rule.permission()) + ". Ask the shop owner.\"}");
        return false;
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

    /** The permission code as a sentence a cashier can read. */
    private static String describe(String permission) {
        return switch (permission) {
            case "sale.create"       -> "ring up sales";
            case "sale.edit"         -> "edit sales";
            case "sale.void"         -> "void an invoice";
            case "sale.delete"       -> "delete sales";
            case "purchase.create"   -> "record purchases";
            case "purchase.edit"     -> "edit purchases";
            case "purchase.delete"   -> "delete purchases";
            case "customer.create"   -> "add customers";
            case "customer.delete"   -> "delete customers";
            case "product.create"    -> "add products";
            case "product.delete"    -> "delete products";
            case "supplier.create"   -> "add suppliers";
            case "supplier.delete"   -> "delete suppliers";
            case "team.create"       -> "create users";
            case "team.edit"         -> "change permissions";
            case "team.view"         -> "see the team";
            case "settings.edit"     -> "change settings";
            case "settings.view"     -> "see settings";
            case "opening.create"    -> "record opening balances";
            case "opening.reverse"   -> "reverse opening balances";
            case "opening.view"      -> "see opening balances";
            default                  -> "do that";
        };
    }
}
