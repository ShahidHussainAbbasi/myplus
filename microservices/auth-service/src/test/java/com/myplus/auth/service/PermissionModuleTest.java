package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EDU-PERM-1 — which permission VOCABULARY a tenant speaks.
 *
 * <h3>The three questions this codebase keeps apart, and why</h3>
 * <ul>
 *   <li>{@code tradeTenant} — does this tenant do shop things (sale, purchase, till)?</li>
 *   <li>{@code rowScopedTenant} — do its rows belong to the member who made them?</li>
 *   <li>{@code moduleOf} — which catalogue of permission codes exists for it? <b>This one.</b></li>
 * </ul>
 *
 * <p>Collapsing any two of them has already cost this platform once. V12's migration read "everyone not
 * already placed" and there are four other modules and a parent portal in the same database, so a
 * {@code ROLE_GUARDIAN} — a parent signing in to see their child's attendance — ended up holding
 * {@code sale.create}. V14 deleted the rows; keeping the questions separate is what stops them coming back.
 *
 * <p>Before this slice the mint gate was {@code tradeTenant}, which was right while only shops had a
 * vocabulary. Now that EDUCATION has one, the question is no longer "does PERM-1 apply?" but "whose
 * words?" — and answering the second with the first would hand a school {@code sale.create}.
 */
class PermissionModuleTest {

    @Test
    @DisplayName("⭐⭐ a school reads the EDUCATION catalogue — the whole point of the slice")
    void educationHasItsOwnVocabulary() {
        assertThat(PermissionService.moduleOf("EDUCATION")).isEqualTo("EDUCATION");
        assertThat(PermissionService.moduleOf("education"))
                .as("case-insensitive, like every other org-type check here")
                .isEqualTo("EDUCATION");
    }

    @Test
    @DisplayName("⭐⭐ a school is NOT handed the shop catalogue — the V12 failure, by another road")
    void educationNeverReadsTheBusinessCatalogue() {
        /*
         * The failure this guards is not a refusal — it is a GRANT. `everything(module)` is what an owner
         * is minted, so an education owner resolving to BUSINESS would receive sale.create, purchase.create
         * and till.open in their token: codes their dashboard cannot use and their role never carried.
         */
        assertThat(PermissionService.moduleOf("EDUCATION")).isNotEqualTo("BUSINESS");
    }

    @Test
    @DisplayName("⭐ a pharmacy reads the BUSINESS catalogue, deliberately")
    void pharmacySharesTheTradeVocabulary() {
        /*
         * A dispensing counter sells, takes payment and receives stock, so the shop vocabulary is the right
         * one for it — and V15 already placed its members on the business built-ins. This is a decision,
         * recorded here so a later reader does not "fix" it into a catalogue of its own.
         */
        assertThat(PermissionService.moduleOf("PHARMA")).isEqualTo("BUSINESS");
        assertThat(PermissionService.moduleOf("BUSINESS")).isEqualTo("BUSINESS");
    }

    @Test
    @DisplayName("⭐⭐ a module with no catalogue gets NO codes — V14's fail-back, still intact")
    void modulesWithoutACatalogueMintNothing() {
        /*
         * V14: "Until another module has a catalog of its own, its members hold NO set, and AuthService
         * then mints exactly the role privileges it minted before PERM-1 existed."
         *
         * null is that state. AuthService skips the whole permission block on it, so these members keep the
         * token they have always had. Returning BUSINESS as a default here would silently re-create V12.
         */
        for (String t : new String[] { "WELFARE", "AGRICULTURE", "APPOINTMENT", "MARKETPLACE", "STOREFRONT" }) {
            assertThat(PermissionService.moduleOf(t)).as("%s has no catalogue of its own", t).isNull();
        }
    }

    @Test
    @DisplayName("an unknown or missing org type mints nothing rather than guessing")
    void unknownTypeIsNotGuessed() {
        // Failing OPEN here would hand codes to a tenant whose type could not be read. The narrow answer
        // is the safe one for a GRANT — the opposite direction from rowScopedTenant, where the narrow
        // answer would hide a shop's own data from it.
        assertThat(PermissionService.moduleOf(null)).isNull();
        assertThat(PermissionService.moduleOf("")).isNull();
        assertThat(PermissionService.moduleOf("SOMETHING_NEW")).isNull();
    }

    @Test
    @DisplayName("⭐ the three questions still disagree where they should — they are not one flag")
    void theThreeQuestionsRemainIndependent() {
        // EDUCATION: has a catalogue, is not a trade tenant, is not row-scoped.
        assertThat(PermissionService.moduleOf("EDUCATION")).isNotNull();
        assertThat(AuthService.tradeTenant("EDUCATION")).isFalse();
        assertThat(AuthService.rowScopedTenant("EDUCATION")).isFalse();

        // MARKETPLACE: row-scoped, but NO catalogue and not a trade tenant. If these ever collapse into
        // one flag, this is the row that fails.
        assertThat(AuthService.rowScopedTenant("MARKETPLACE")).isTrue();
        assertThat(PermissionService.moduleOf("MARKETPLACE")).isNull();
        assertThat(AuthService.tradeTenant("MARKETPLACE")).isFalse();
    }
}
