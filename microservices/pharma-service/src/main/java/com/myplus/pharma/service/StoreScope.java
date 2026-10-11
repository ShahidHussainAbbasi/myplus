package com.myplus.pharma.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;

/**
 * HMS H4 — which pharmacy branches (business-service stores) the caller works at, for prescriptions. The SAME rule
 * multi-location applies to sales and purchases (P2b / P2c):
 * <ul>
 *   <li>the owner sees every branch (grants never narrow an owner);</li>
 *   <li>a member with branch grants sees those branches AND rows with no branch (counter prescriptions, unrouted
 *       doctors, everything before H4);</li>
 *   <li>a member with no grants (a single-branch business) is unconstrained — unchanged behaviour.</li>
 * </ul>
 */
@Component
public class StoreScope {

    /** A placeholder that matches no store: JPQL {@code IN ()} is not valid, and "all" is passed separately. */
    static final List<Long> NONE = List.of(-1L);

    public boolean seesAll() {
        AuthenticatedUser u = CurrentUser.get().orElse(null);
        if (u == null) return true;   // background / internal: org scope only, as before
        boolean owner = u.getAuthorities() != null && u.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        Set<Long> mine = u.getAccessibleLocationIds();
        return owner || mine == null || mine.isEmpty();
    }

    public List<Long> stores() {
        if (seesAll()) return NONE;
        return List.copyOf(CurrentUser.get().map(AuthenticatedUser::getAccessibleLocationIds).orElse(Set.of()));
    }

    /** Anti-IDOR for one prescription (get / dispense / change / cancel / history). */
    public boolean canAccess(Long storeId) {
        return storeId == null || seesAll() || stores().contains(storeId);
    }
}
