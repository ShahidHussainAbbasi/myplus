package com.myplus.expense.service;

import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * Who may do what, in one place.
 *
 * <h3>The capability check fails CLOSED</h3>
 * Expense management is an opt-in module (EX-0a). A write is allowed only when the caller's token says the
 * tenant has switched it on. An UNRESOLVED capability set (null) is refused too — unlike
 * {@code CurrentUser.capabilityAllowed}, which is permissive by design and documented as "not for the ledger".
 *
 * <h3>Tiers (EX-1)</h3>
 * Any member of the business records an expense — the money has already left. Owner and admin see every
 * voucher and may void; a user sees their own. Dedicated EXPENSE_* privileges arrive with staff claims (EX-6).
 */
@Component
public class ExpenseAccess {

    public static final String CAPABILITY = "expenseManagement";
    public static final String CLAIMS = "expenseClaims";    // EX-6

    public Long org() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new ValidationException("No active organisation.");
        return org;
    }

    public Long userId() { return CurrentUser.userId(); }

    public void assertModuleOn() {
        Set<String> caps = CurrentUser.capabilities();
        if (caps == null || !caps.contains(CAPABILITY)) {
            throw new ValidationException("Expense management is not switched on for this business. "
                    + "An owner can switch it on in Settings → Configuration.");
        }
    }

    /** EX-6 — claims need their own switch, on top of Expense management. */
    public void assertClaimsOn() {
        assertModuleOn();
        Set<String> caps = CurrentUser.capabilities();
        if (caps == null || !caps.contains(CLAIMS)) {
            throw new ValidationException("Expense claims are not switched on for this business. "
                    + "An owner can switch them on in Configuration → Modules.");
        }
    }

    /**
     * EX-6 — may approve a claim: the business's owner or an admin. NOT the platform operator (SUPER): approving
     * posts money to a tenant's books, and the operator is support only (design §6.3).
     */
    public boolean canApprove() {
        return CurrentUser.get().map(u -> u.getAuthorities() != null && u.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(n -> "ROLE_OWNER".equals(n) || "ADMIN_PRIVILEGE".equals(n))).orElse(false);
    }

    /** Owner or admin: sees every voucher in the business. */
    public boolean seesAll() {
        return CurrentUser.get().map(ExpenseAccess::isOwnerOrAdmin).orElse(false);
    }

    /** The user filter for reads: null (everyone) for owner/admin, the caller for a user. */
    public Long visibleUserId() {
        return seesAll() ? null : userId();
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
