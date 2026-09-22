package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SCOPE-1 — which tenants honour "own records only", and why that is not the same question as PERM-1 codes.
 *
 * <h3>The defect</h3>
 * Row scope was decided by {@code tradeTenant} alone — {@code (!businessTenant || isOwner) -> "ALL"} — so every
 * member of a non-trade tenant was minted with {@code scope.ALL}, which {@code LocationScope.seesWholeOrg()}
 * reads. A MARKETPLACE order booker (ROLE_ORDER_BOOKER, no ADMIN_PRIVILEGE, no SUPER_PRIVILEGE, no permission
 * set) could therefore list, open BY ID and print every other member's quotes, and an audit-scoped read
 * returned outlets the owner had created. Four gate specs failed on it at once:
 * quote-visibility (×2), quote-document (⭐7) and order-booker.
 *
 * <p>The services were never wrong: {@code SalesQuoteService.list()/load()} branch on
 * {@code callerSeesWholeOrg()} and answer "not found" for a foreign quote. The scoping was defeated at MINT.
 */
class RowScopedTenantTest {

    @Test
    @DisplayName("⭐⭐ a MARKETPLACE tenant is row-scoped — the defect: its bookers saw the whole org")
    void marketplaceIsRowScoped() {
        assertThat(AuthService.rowScopedTenant("MARKETPLACE")).isTrue();
        assertThat(AuthService.rowScopedTenant("marketplace")).as("case-insensitive, as every other check here")
                .isTrue();
    }

    @Test
    @DisplayName("⭐⭐ scope and ACTION CODES are different questions — marketplace gains no codes from this")
    void scopeDoesNotWidenPermissionCodes() {
        /*
         * The distinction this whole slice rests on. rowScopedTenant decides whose ROWS a member may read;
         * tradeTenant decides whether PERM-1 codes are minted at all. Widening the latter would hand
         * marketplace members codes their role never carried — the V12 mistake that left a ROLE_GUARDIAN
         * holding sale.create, which V14 had to clean up row by row.
         */
        assertThat(AuthService.rowScopedTenant("MARKETPLACE")).isTrue();
        assertThat(AuthService.tradeTenant("MARKETPLACE")).as("still NOT a trade tenant — no PERM-1 codes")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ the trade tenants keep both answers, exactly as PERM-2 left them")
    void tradeTenantsAreUnchanged() {
        for (String t : new String[] { "BUSINESS", "PHARMA" }) {
            assertThat(AuthService.tradeTenant(t)).as("%s mints PERM-1 codes", t).isTrue();
            assertThat(AuthService.rowScopedTenant(t)).as("%s is row-scoped", t).isTrue();
        }
    }

    @Test
    @DisplayName("⭐⭐ the OTHER modules keep whole-org visibility — narrowing them is a product decision, not a side effect")
    void otherModulesAreNotNarrowedBySideEffect() {
        /*
         * EDUCATION, WELFARE, AGRICULTURE and APPOINTMENT were built on shared org-wide reads: a school's staff
         * all see the same students and fee collections. Flipping them to "own records only" while fixing
         * marketplace would hide data those modules expect to share, and would do it silently — the failure
         * mode being an empty screen rather than an error. Each is its own decision, made with its own module
         * in view.
         */
        for (String t : new String[] { "EDUCATION", "WELFARE", "AGRICULTURE", "APPOINTMENT" }) {
            assertThat(AuthService.rowScopedTenant(t)).as("%s keeps whole-org scope", t).isFalse();
            assertThat(AuthService.tradeTenant(t)).as("%s mints no PERM-1 codes either", t).isFalse();
        }
    }

    @Test
    @DisplayName("an unknown or missing org type is not row-scoped — the permissive answer it has always had")
    void unknownTypeKeepsTodaysAnswer() {
        // Failing CLOSED here would narrow a tenant whose type could not be read to "own records only" — a
        // shop staring at an empty dashboard because one column was null. Same direction Shape.byCode chose.
        assertThat(AuthService.rowScopedTenant(null)).isFalse();
        assertThat(AuthService.rowScopedTenant("")).isFalse();
        assertThat(AuthService.rowScopedTenant("SOMETHING_NEW")).isFalse();
    }
}
