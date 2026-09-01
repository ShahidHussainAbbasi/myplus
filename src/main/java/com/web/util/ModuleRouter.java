package com.web.util;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;

import com.persistence.model.User;

/**
 * B2B P0.5 — where a logged-in user lands, decided in ONE place.
 *
 * <h3>Why this class exists</h3>
 * The type&rarr;dashboard mapping used to be written twice, in
 * {@code MySimpleUrlAuthenticationSuccessHandler.determineTargetUrl} (post-login) and
 * {@code AppController.dashboard()} (the {@code /dashboard} route) — and the two copies had already drifted
 * apart: {@code AppController} had no {@code APPOINTMENT} case and sent those users to the landing page,
 * while the login handler routed them to {@code /appointmentDashboard}. The same user therefore landed in
 * two different places depending on how they arrived. One map, one rule, per DRY.
 *
 * <h3>The rule</h3>
 * <pre>
 *   activeOrgType  — the module of the tenant the user is working in   (preferred)
 *   userType       — the single type stamped on the person             (fallback)
 *   "/"            — neither resolves
 * </pre>
 *
 * Preferring the <em>organization</em> is what lets ONE login reach every module: a user who owns a shop and
 * a school switches tenant and the platform follows them, instead of pinning them to whichever type their
 * account was created with.
 *
 * <p>The fallback is not defensive padding — {@code Organization.type} is nullable and every tenant created
 * before it was populated holds NULL. Those users must keep landing exactly where they land today, which is
 * what {@code userType} gives them.
 *
 * <h3>What this does NOT do</h3>
 * It picks a <b>screen</b>, never a permission. Every read and write remains gated by org scoping and
 * privilege checks; landing on a dashboard the user has no privileges for shows a page whose sections
 * refuse to load. Routing is not authorization.
 */
public final class ModuleRouter {

    /** Fallback when no module resolves: the public landing page, which never 404s. */
    public static final String LANDING = "/";

    /**
     * Commerce verticals all share the ONE dashboard, white-labelled by module (slice 36):
     * POS = BUSINESS, Pharmacy = PHARMA, Store = MARKETPLACE. No per-vertical routes.
     */
    private static final Set<String> COMMERCE_TYPES = Set.of("BUSINESS", "PHARMA", "MARKETPLACE");

    /**
     * The dashboard a KNOWN module owns in the monolith UI.
     *
     * <h3>C2 — an unknown type no longer bounces to the landing page</h3>
     * {@code Organization.type} is a free-text column with no enum, no validation and no allow-list, while
     * this map has seven entries. So a tenant onboarded as {@code MOBILE} — a mobile shop, which the product
     * is expressly meant to serve — was silently sent to {@code /} on <b>every login</b>. Nothing failed,
     * nothing logged, and the shop simply never reached a dashboard. Free text at the column, enumerated at
     * the router: that mismatch is the bug.
     *
     * <p>The old javadoc defended {@code /} as better than a {@code /<type>Dashboard} guess that would 404,
     * and that much is right — a guess built from the type would 404. But those are not the only two options:
     * a FIXED fallback to a dashboard that certainly exists is better than both.
     *
     * <p>So the rule is now:
     * <pre>
     *   blank / no type   -> LANDING     we genuinely do not know who this is; saying so is honest
     *   known type        -> its own dashboard
     *   unknown type      -> commerce    somebody DID set a type, so this is a business; show them one
     * </pre>
     *
     * <p>Commerce is the right fallback rather than a neutral page because every non-education vertical the
     * product has ever onboarded is a trade business, and {@code CommerceDashboardController} already renders
     * POS wording for any module it does not recognise. Routing is not authorization: every section on that
     * page stays gated by privilege, org scope and — from C1 — capability.
     */
    /** The dashboard every trade vertical shares, and the safe landing place for an unrecognised type. */
    public static final String COMMERCE_DASHBOARD = "/businessDashboard";

    /**
     * E2 — the platform operator's console. Not a tenant dashboard, and that is the point.
     *
     * <p>Its own constant because {@code ADMIN} is the one entry in the map below that is NOT a business
     * type: every other key names a kind of customer, this one names the people who run the platform.
     */
    public static final String PLATFORM_DASHBOARD = "/platformDashboard";

    private static final Map<String, String> DASHBOARD_BY_TYPE = Map.of(
            "BUSINESS",    COMMERCE_DASHBOARD,
            "PHARMA",      COMMERCE_DASHBOARD,
            "MARKETPLACE", COMMERCE_DASHBOARD,
            "EDUCATION",   "/educationDashboard",
            "WELFARE",     "/welfareDashboard",
            "AGRICULTURE", "/agricultureDashboard",
            "APPOINTMENT", "/appointmentDashboard",
            /*
             * E2 — the MaxTheService operator.
             *
             * Without this entry `ADMIN` is an unknown type, so the fallback above sends the platform
             * operator to COMMERCE_DASHBOARD: a shopkeeper's till, scoped to the operator's own accidental
             * organization. Not a security hole — org scoping holds and every read is that org's — but
             * comprehensively the wrong product, and the reason the operator portal had nowhere to live.
             *
             * The commerce fallback stays exactly as it is for everything else. It was the right call for an
             * unrecognised BUSINESS type; it was never meant to answer for a user who is not a customer.
             */
            "ADMIN",       PLATFORM_DASHBOARD);

    /**
     * Slice 3.3 — the PORTAL audiences, routed by ROLE and not by module.
     *
     * <p><b>Why this map has to exist.</b> Everything above keys on the user's module, and a guardian and a
     * student are both {@code EDUCATION} — so without this they land on {@code /educationDashboard}, the
     * staff shell, whose every read {@code PortalScopeFilter} then answers with 404. They would arrive at a
     * page built entirely from data they are not allowed to have. <b>This was already true of 3.1's
     * guardian, and is fixed here rather than left for a second audience to hit.</b>
     *
     * <p>Checked BEFORE the module map, because the portal role is the more specific fact: it says which
     * SURFACE this person gets, while the module only says which product they belong to.
     */
    private static final Map<String, String> PORTAL_DASHBOARD_BY_ROLE = Map.of(
            "ROLE_GUARDIAN", "/guardianDashboard",
            "ROLE_STUDENT",  "/studentDashboard");

    private ModuleRouter() {
    }

    /**
     * The portal dashboard for a session carrying a portal role, or {@code null} for everyone else.
     *
     * <p>Null — not {@link #LANDING} — so a caller can tell "this is not a portal session" from "this is a
     * portal session with nowhere to go", and fall through to the normal module routing.
     */
    public static String portalDashboardFor(final Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) {
            return null;
        }
        for (GrantedAuthority a : authorities) {
            String dashboard = a == null ? null : PORTAL_DASHBOARD_BY_ROLE.get(a.getAuthority());
            if (dashboard != null) {
                return dashboard;
            }
        }
        return null;
    }

    /**
     * The module this user is currently working in: the active organization's type when known, otherwise
     * their own user type. Returns {@code null} when neither is set.
     */
    public static String moduleOf(final User user) {
        if (user == null) {
            return null;
        }
        String orgType = normalize(user.getActiveOrgType());
        return orgType != null ? orgType : normalize(user.getUserType());
    }

    /**
     * The dashboard path this user should land on — {@link #LANDING} when no module resolves, or when the
     * resolved module has no monolith dashboard.
     */
    public static String dashboardFor(final User user) {
        return dashboardForModule(moduleOf(user));
    }

    /**
     * The dashboard for an already-resolved module name.
     *
     * <p>Blank &rarr; {@link #LANDING}. Unknown-but-present &rarr; {@link #COMMERCE_DASHBOARD}; see the
     * {@code DASHBOARD_BY_TYPE} javadoc for why those two cases answer differently.
     */
    public static String dashboardForModule(final String module) {
        String key = normalize(module);
        if (key == null) {
            // No type at all. We do not know what this user is, and guessing a business dashboard for a
            // module that may have no monolith UI would be a different kind of wrong.
            return LANDING;
        }
        return DASHBOARD_BY_TYPE.getOrDefault(key, COMMERCE_DASHBOARD);
    }

    /** True when the module is one of the commerce verticals sharing {@code /businessDashboard}. */
    public static boolean isCommerce(final String module) {
        String key = normalize(module);
        return key != null && COMMERCE_TYPES.contains(key);
    }

    /** Upper-cased and trimmed, or null for anything blank — a blank type must behave exactly like a missing one. */
    private static String normalize(final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }
}
