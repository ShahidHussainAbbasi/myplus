package com.web.util;

import java.util.Map;
import java.util.Set;

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
     * Every module that owns a dashboard in the monolith UI. A type absent from this map falls back to the
     * landing page rather than a {@code /<type>Dashboard} guess that would 404 — a microservice-only module
     * with no monolith UI yet is a normal state, not an error.
     */
    private static final Map<String, String> DASHBOARD_BY_TYPE = Map.of(
            "BUSINESS",    "/businessDashboard",
            "PHARMA",      "/businessDashboard",
            "MARKETPLACE", "/businessDashboard",
            "EDUCATION",   "/educationDashboard",
            "WELFARE",     "/welfareDashboard",
            "AGRICULTURE", "/agricultureDashboard",
            "APPOINTMENT", "/appointmentDashboard");

    private ModuleRouter() {
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

    /** The dashboard for an already-resolved module name. Null/unknown &rarr; {@link #LANDING}. */
    public static String dashboardForModule(final String module) {
        String key = normalize(module);
        if (key == null) {
            return LANDING;
        }
        return DASHBOARD_BY_TYPE.getOrDefault(key, LANDING);
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
