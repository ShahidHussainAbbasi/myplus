package com.myplus.business_service.util;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Thin adapter over the shared {@link CurrentUser} accessor (slice 33, Phase 2b). Kept as an injectable
 * bean so existing controllers/services keep working unchanged; new code should use {@link CurrentUser}
 * directly. The previous per-service copy had diverged identity-reading logic plus dead helpers — all
 * removed in favour of the single source of truth.
 */
@Component
public class RequestUtil {

    @Nullable
    public AuthenticatedUser getCurrentUser() {
        return CurrentUser.get().orElse(null);
    }

    /**
     * Business data-visibility policy (single source of truth): a caller sees the WHOLE organization's
     * customers / sales / purchases when they are an owner/super ({@code SUPER_PRIVILEGE}) OR an admin
     * ({@code ADMIN_PRIVILEGE}). A plain member ({@code USER}) sees only the rows they created.
     * Centralised here so the Customer/Sell/Purchase list controllers share one rule.
     */
    public boolean callerSeesWholeOrg() {
        return com.myplus.common.security.LocationScope.seesWholeOrg();
    }

    /**
     * Multi-location (Pattern A): the stores this caller may access (gateway X-Location-Ids). EMPTY means
     * "no location constraint" — single-store / unassigned / legacy — so scoped reads apply no store filter
     * and behave exactly as before. Non-empty means constrain reads to these stores.
     */
    public java.util.Set<Long> accessibleStoreIds() {
        return com.myplus.common.security.LocationScope.accessible();
    }

    /** The active store to stamp on new records (gateway X-Location-Id); null = single-store/unset. */
    public Long activeStoreId() {
        return com.myplus.common.security.LocationScope.active();
    }

    /**
     * Anti-IDOR for a single record (design §2.7): may this caller touch a row stamped with {@code storeId}?
     * The list queries already filter by store, but a read-by-id or a mutation takes an id straight from the
     * client, so the same rule has to be applied per record — otherwise an admin at Store B can open and edit
     * a Store-A invoice simply by knowing its id.
     *
     * Permissive exactly where the design says the store dimension is absent, so single-store tenants and
     * legacy data behave as before:
     *   - owner/super              -> whole org, every store;
     *   - caller holds no grants   -> no store constraint (single-store / unassigned / legacy);
     *   - row has no store         -> legacy row, reachable (the caller's own-record check still applies).
     */
    public boolean canAccessStore(@Nullable Long storeId) {
        return com.myplus.common.security.LocationScope.canAccess(storeId);
    }

    /**
     * The OWNER (SUPER_PRIVILEGE) always sees the WHOLE org across ALL stores — store grants never narrow an
     * owner. Admins (ADMIN_PRIVILEGE, no SUPER) ARE constrained to their granted stores. Distinguishes the two.
     */
    public boolean isOwnerSuper() {
        return com.myplus.common.security.LocationScope.isOwnerSuper();
    }

    /** The authorities that hold EVERY permission code — the same set as the monolith's MenuAccess / PermissionInterceptor. */
    private static final java.util.Set<String> HOLDS_ALL =
            java.util.Set.of("ROLE_OWNER", "SUPER_PRIVILEGE", "ADMIN_PRIVILEGE", "ADMIN_ROLE");

    /**
     * SRA-2 — does the caller hold permission {@code code} (a PERM-1 code riding the token's privileges claim)? The
     * owner, an administrator and the platform roles hold every code. Data, not a role name: which members hold a code
     * is the owner's permission set.
     *
     * <p>⚠ NOT {@link #callerSeesWholeOrg()}. That answers "which ROWS may this person see" (the set's Sees: Own/All
     * scope); this answers "may this person do THIS". The territory rule used the first for the second, so a member
     * whose set had scope ALL could assign territories and was never narrowed to their own outlets (D6a red gate).
     */
    public boolean holds(String code) {
        return CurrentUser.get()
                .filter(u -> u.getAuthorities() != null)
                .map(u -> u.getAuthorities().stream().anyMatch(a ->
                        HOLDS_ALL.contains(a.getAuthority()) || code.equals(a.getAuthority())))
                .orElse(false);
    }
}
