package com.myplus.market.service;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * Who may do what on the marketplace, in one place.
 *
 * <h3>Three audiences, never mixed</h3>
 * <ul>
 *   <li><b>Operator</b> — the MaxTheService platform operator ({@code ROLE_ADMIN}, never {@code ADMIN_PRIVILEGE},
 *       which every tenant owner holds). Writes the platform's own rows: policies, seller decisions.</li>
 *   <li><b>Seller</b> — an ordinary tenant that switched {@code marketplaceSelling} on. Its org is ALWAYS the
 *       caller's own from the token; no request carries an organisation id (source design §22).</li>
 *   <li><b>Customer</b> — MP-4.</li>
 * </ul>
 *
 * <h3>The capability check fails CLOSED</h3>
 * Selling on the marketplace commits the seller to orders and settlements. An UNRESOLVED capability set (null) is
 * refused, like expense-service's {@code ExpenseAccess} and unlike the permissive
 * {@code CurrentUser.capabilityAllowed}.
 */
@Component
public class MarketAccess {

    public static final String CAPABILITY = "marketplaceSelling";

    private final Long platformOrgId;

    public MarketAccess(@Value("${market.platform-org-id}") Long platformOrgId) {
        this.platformOrgId = platformOrgId;
    }

    /** The org that owns the marketplace's own rows (ruling R-2). */
    public Long platformOrg() { return platformOrgId; }

    public Long userId() { return CurrentUser.userId(); }

    /** Belt and braces behind {@code @PreAuthorize}: a refactor that drops the annotation still fails closed. */
    public void assertOperator() {
        if (!CurrentUser.isPlatformOperator()) throw new AccessDeniedException("Platform operator only.");
    }

    /** The calling seller's org, from the token. */
    public Long sellerOrg() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new ValidationException("No active organisation.");
        return org;
    }

    public void assertSellingOn() {
        Set<String> caps = CurrentUser.capabilities();
        if (caps == null || !caps.contains(CAPABILITY)) {
            throw new ValidationException("Marketplace selling is not switched on for this business. "
                    + "An owner can switch it on in Settings → Configuration.");
        }
    }
}
