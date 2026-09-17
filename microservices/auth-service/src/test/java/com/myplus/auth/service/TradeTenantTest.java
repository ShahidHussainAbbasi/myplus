package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PERM-2 — which tenants are minted PERM-1 permission codes.
 *
 * <p>The rule decides whether a member holds {@code product.create}, {@code sale.create} and the rest at all, and
 * both directions of it have already cost something real:
 * <ul>
 *   <li>too NARROW (BUSINESS only) — every non-owner pharmacy member held nothing and was refused every mapped
 *       action, while the affordances gated on those codes disappeared from the screen;</li>
 *   <li>too WIDE (V12's "everyone") — a {@code ROLE_GUARDIAN} parent was placed on a shop set and carried
 *       {@code sale.create}, which V14 had to delete.</li>
 * </ul>
 *
 * <p>Pure, no Spring: this is the one decision, tested on its own.
 */
class TradeTenantTest {

    @Test
    @DisplayName("⭐ a PHARMACY trades — it reuses the till, the purchase screen and the same permission map")
    void pharmacyIsATradeTenant() {
        assertThat(AuthService.tradeTenant("PHARMA")).isTrue();
        assertThat(AuthService.tradeTenant("BUSINESS")).isTrue();
    }

    @Test
    @DisplayName("⭐ a module that does not trade is NOT minted shop permissions (the V14 scar)")
    void otherModulesAreNotTradeTenants() {
        // A ROLE_GUARDIAN parent on one of these held sale.create until V14 deleted it.
        assertThat(AuthService.tradeTenant("EDUCATION")).isFalse();
        assertThat(AuthService.tradeTenant("WELFARE")).isFalse();
        assertThat(AuthService.tradeTenant("AGRICULTURE")).isFalse();
        assertThat(AuthService.tradeTenant("APPOINTMENT")).isFalse();
        assertThat(AuthService.tradeTenant("CAMPAIGN")).isFalse();
        assertThat(AuthService.tradeTenant("ANALYTICS")).isFalse();
    }

    @Test
    @DisplayName("MARKETPLACE is deliberately OUT pending its own ruling — pinned so nobody adds it by accident")
    void marketplaceIsNotIncludedYet() {
        // Its members also hold no codes and storefront-gl.cy.js shows one posting /addProduct, but marketplace
        // reaches a different dashboard: whether its staff should hold shop permissions is a product decision.
        // If that ruling is ever made, this test is the place it gets recorded.
        assertThat(AuthService.tradeTenant("MARKETPLACE")).isFalse();
    }

    @Test
    @DisplayName("an unknown, blank or missing type mints NOTHING — the safe direction")
    void unknownTypeIsNotATradeTenant() {
        // A tenant with no active organisation, or a type added later, must fall through to role privileges
        // alone rather than silently gaining a shop's permissions.
        assertThat(AuthService.tradeTenant(null)).isFalse();
        assertThat(AuthService.tradeTenant("")).isFalse();
        assertThat(AuthService.tradeTenant("null")).isFalse();
        assertThat(AuthService.tradeTenant("SOMETHING_NEW")).isFalse();
    }

    @Test
    @DisplayName("the check is case-insensitive — the column is read as a String, not an enum")
    void caseDoesNotMatter() {
        assertThat(AuthService.tradeTenant("pharma")).isTrue();
        assertThat(AuthService.tradeTenant("Business")).isTrue();
    }
}
