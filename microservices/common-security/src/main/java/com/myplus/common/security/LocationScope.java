package com.myplus.common.security;

import java.util.Collections;
import java.util.Set;

/**
 * The role×location visibility policy (multi-location design §2.3/§2.7), in ONE place for every vertical —
 * business Stores, education Branches (schools), pharma/marketplace as they land. Each service's RequestUtil
 * delegates here rather than keeping its own copy, so the rule cannot drift between verticals.
 *
 * <p>Everything is derived from the signed JWT via the gateway headers ({@code X-Location-Id},
 * {@code X-Location-Ids}, {@code X-Loc-Role}) — a client can never widen its own scope.
 *
 * <p>The policy is deliberately permissive exactly where the location dimension is absent, so a
 * single-location tenant and all pre-migration data behave precisely as they did before:
 * <ul>
 *   <li>owner/super — the whole org, every location; grants never narrow an owner;</li>
 *   <li>no grants — no location constraint (single-location / unassigned / legacy);</li>
 *   <li>a record with no location — legacy row, still reachable (the own-record rule still applies).</li>
 * </ul>
 */
public final class LocationScope {

    private LocationScope() { }

    /** The locations this caller may access. EMPTY = no location constraint (see class javadoc). */
    public static Set<Long> accessible() {
        return CurrentUser.get()
                .map(AuthenticatedUser::getAccessibleLocationIds)
                .filter(s -> s != null)
                .orElse(Collections.emptySet());
    }

    /** The location new records are stamped with; null = single-location, or several held and none chosen yet. */
    public static Long active() {
        return CurrentUser.get().map(AuthenticatedUser::getActiveLocationId).orElse(null);
    }

    /** Owner/super: the whole org across ALL locations, always. */
    public static boolean isOwnerSuper() {
        return hasAuthority("SUPER_PRIVILEGE");
    }

    /** Whole-org viewer: an owner OR an admin — sees other users' records (within their locations). */
    public static boolean seesWholeOrg() {
        return isOwnerSuper() || hasAuthority("ADMIN_PRIVILEGE");
    }

    /**
     * Anti-IDOR for a single record: may this caller touch a row stamped with {@code locationId}? The list
     * queries already filter by location, but a read-by-id or a mutation takes an id straight from the client,
     * so the same rule must be re-applied per record — otherwise an admin at location B can open and edit a
     * location-A record simply by knowing its id.
     */
    public static boolean canAccess(Long locationId) {
        if (isOwnerSuper()) return true;
        Set<Long> mine = accessible();
        if (mine.isEmpty() || locationId == null) return true;
        return mine.contains(locationId);
    }

    private static boolean hasAuthority(String authority) {
        return CurrentUser.get()
                .filter(u -> u.getAuthorities() != null)
                .map(u -> u.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority())))
                .orElse(false);
    }
}
