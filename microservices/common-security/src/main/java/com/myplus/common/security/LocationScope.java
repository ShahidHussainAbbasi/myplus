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

    /**
     * PERM-1 — the permission set's answer to "whose records?", carried as an authority.
     *
     * <h3>Why an AUTHORITY and not a new field on the principal</h3>
     * Authorities already travel the whole way: minted into the `privileges` claim, forwarded by the
     * gateway, rebuilt by HeaderAuthFilter, and read by @PreAuthorize and sec:authorize alike. A new
     * field on AuthenticatedUser would have meant touching its constructor, the header filter, the
     * gateway's forwarding and the monolith's session builder — four places to keep in step, and the day
     * one is missed the scope silently reverts to OWN for that path only. This rides a rail that is
     * already correct.
     */
    public static final String SCOPE_ALL = "scope.ALL";

    /**
     * Whole-org viewer: sees other users' records (within their locations).
     *
     * <h3>Three ways in, and the third is new</h3>
     * <ul>
     *   <li>an OWNER or SUPER — always, and not expressible as a set (they hold everything by design);</li>
     *   <li>an ADMIN — the long-standing rule, unchanged;</li>
     *   <li>⭐ a member whose PERMISSION SET says {@code ALL} — the "Sees" control on the matrix screen.</li>
     * </ul>
     *
     * <p>The third existed on the screen and governed nothing: the choice was stored on the set, minted
     * into the token, and read by no one. An owner set a member to "All records in this shop" and watched
     * nothing change, because the ROLE decided. Two answers to one question, and the dropdown was the one
     * nobody consulted.
     *
     * <p>⚠ Scoped deliberately to a READ widening. It grants no action: a member still cannot ring up a
     * sale without {@code sale.create}, and this only decides how much of the shop the actions they DO
     * hold can see. The matrix says what; this says whose.
     *
     * <p>⚠ It also cannot NARROW an admin. An admin has seen the whole shop since long before permission
     * sets existed, and taking that away from every admin in the product on the deploy that introduces
     * this is the G-5 failure the built-in sets exist to prevent. Widening is safe to infer; narrowing is
     * a decision somebody has to make deliberately.
     */
    public static boolean seesWholeOrg() {
        return isOwnerSuper() || hasAuthority("ADMIN_PRIVILEGE") || hasAuthority(SCOPE_ALL);
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
