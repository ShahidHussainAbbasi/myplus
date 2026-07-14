package com.myplus.education.util;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Thin adapter over the shared {@link CurrentUser} accessor (slice 33, Phase 2b). Kept as an injectable
 * bean so existing controllers keep working unchanged; new code should use {@link CurrentUser} directly.
 */
@Component
public class RequestUtil {

    @Nullable
    public AuthenticatedUser getCurrentUser() {
        return CurrentUser.get().orElse(null);
    }

    // ── Multi-location P4: the education "location" is the School (branch). Same policy as business Stores —
    // it lives once in common-security's LocationScope, so the two verticals cannot drift apart.

    /** The schools (branches) this caller may access. EMPTY = no branch constraint (single-branch / legacy). */
    public java.util.Set<Long> accessibleSchoolIds() {
        return com.myplus.common.security.LocationScope.accessible();
    }

    /** The school new records are stamped with; null = single-branch, or several held and none chosen yet. */
    public Long activeSchoolId() {
        return com.myplus.common.security.LocationScope.active();
    }

    /** Owner/super: the whole org across ALL branches, always — grants never narrow an owner. */
    public boolean isOwnerSuper() {
        return com.myplus.common.security.LocationScope.isOwnerSuper();
    }

    /** Whole-org viewer (owner or admin/principal): sees other users' records within their branches. */
    public boolean callerSeesWholeOrg() {
        return com.myplus.common.security.LocationScope.seesWholeOrg();
    }

    /** Anti-IDOR: may this caller touch a record in {@code schoolId}? (See LocationScope for the exact rule.) */
    public boolean canAccessSchool(@Nullable Long schoolId) {
        return com.myplus.common.security.LocationScope.canAccess(schoolId);
    }
}
