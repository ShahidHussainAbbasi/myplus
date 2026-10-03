package com.myplus.marketplace.multiseller.service;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * MKT-0a — who may do what on the marketplace, in one place (the ExpenseAccess shape).
 *
 * <h3>The capability check fails CLOSED</h3>
 * {@code marketplaceSelling} is an opt-in module. An UNRESOLVED capability set (null) is refused, unlike
 * {@code CurrentUser.capabilityAllowed}, which is permissive by design and documented as not for money paths — and
 * a seller account leads to money.
 *
 * <h3>The operator is ROLE_ADMIN, never ADMIN_PRIVILEGE</h3>
 * Every tenant owner holds ADMIN_PRIVILEGE inside their own org; keying operator actions on it would let any
 * tenant approve itself. {@link CurrentUser#isPlatformOperator()} is the platform's one definition.
 */
@Component
public class SellerAccess {

    public static final String CAPABILITY = "marketplaceSelling";

    public Long org() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new ValidationException("No active organisation.");
        return org;
    }

    public Long userId() {
        return CurrentUser.userId();
    }

    public boolean capabilityOn() {
        Set<String> caps = CurrentUser.capabilities();
        return caps != null && caps.contains(CAPABILITY);
    }

    public void assertCapabilityOn() {
        if (!capabilityOn())
            throw new ValidationException("Selling on the MaxTheService marketplace is not switched on for this "
                    + "business. An owner can switch it on in Settings → Configuration.");
    }

    /** Accepting agreements binds the business: owner or admin only, never a user-tier member. */
    public void assertOwnerOrAdmin() {
        boolean ok = CurrentUser.get().map(SellerAccess::isOwnerOrAdmin).orElse(false);
        if (!ok)
            throw new ValidationException("Only the owner or an admin can accept the marketplace agreements "
                    + "for this business.");
    }

    public void assertOperator() {
        if (!CurrentUser.isPlatformOperator()) throw new AccessDeniedException("Access denied");
    }

    private static boolean isOwnerOrAdmin(AuthenticatedUser u) {
        if (u.getAuthorities() == null) return false;
        for (GrantedAuthority a : u.getAuthorities()) {
            String n = a.getAuthority();
            if ("ROLE_OWNER".equals(n) || "ADMIN_PRIVILEGE".equals(n) || "SUPER_PRIVILEGE".equals(n)) return true;
        }
        return false;
    }
}
