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
            // ⚠ ONLY THE WRITE. Reading the config is in ALWAYS below — see the note there.
            new Rule("POST", "/saveBusinessConfig",    "settings.edit"),

            // ── the two the owner asked for by name ────────────────────────────────────────────
            new Rule("POST", "/updateSell",            "sale.edit"),
            new Rule("POST", "/addSell",               "sale.create"),
            new Rule("POST", "/updatePurchase",        "purchase.edit"),
            new Rule("POST", "/addPurchase",           "purchase.create"),

            // ⚠ "Edit a product" (product.edit) is NOT mapped yet, and that is a HOLD, not an oversight
            // (2026-09-14). /updateProduct is unmapped, so every member may edit products. Mapping it was coded
            // and pulled before build: pharmacy tenants are org type PHARMA, AuthService mints PERM-1 codes for
            // BUSINESS tenants only, so every non-owner pharmacy member holds no codes and the rule would take
            // product editing away from them all (they are already refused /addProduct today, verified live).
            // Map it once PHARMA members carry codes. See slices/blk-4-product-optimistic-lock.md §6.

            // ── stock in: the per-row "Add to on-hand" on the Product grid ──────────────────────────
            // It used to fall to the "/addProduct" prefix rule below and demand product.create, "Add a product",
            // which is the wrong permission for putting units on a shelf. ABOVE /addProduct for the same prefix
            // reason as the sticker rules. Counted before changing (auth DB, 2026-09-14): no member on a set
            // holds one of product.create / stock.create without the other, so nobody gains or loses it today.
            // /adjustProductStock is deliberately NOT mapped here: it is unmapped (allowed) now, and gating it
            // would take stock correction away from members who have it.
            new Rule("POST", "/addProductStock",       "stock.create"),

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
            case "product.edit"      -> "edit products";
            case "product.delete"    -> "delete products";
            case "supplier.create"   -> "add suppliers";
            case "stock.create"      -> "add stock";
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
