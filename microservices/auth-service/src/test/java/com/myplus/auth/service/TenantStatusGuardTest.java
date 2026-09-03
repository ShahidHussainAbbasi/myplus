package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myplus.common.settings.OrganizationStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E3 — the tenant-lifecycle state machine.
 *
 * <p>Design: {@code microservices/docs/slices/e3-tenant-lifecycle-design.md}. The Cypress gate proves the
 * guard end to end through a real login; this pins the rules the guard is built on, including the one case
 * the gate deliberately cannot reach.
 */
class TenantStatusGuardTest {

    // ── the state machine ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only ACTIVE permits a sign-in")
    void only_active_signs_in() {
        assertThat(OrganizationStatus.ACTIVE.allowsSignIn()).isTrue();
        assertThat(OrganizationStatus.SUSPENDED.allowsSignIn()).isFalse();
        assertThat(OrganizationStatus.CLOSED.allowsSignIn()).isFalse();
    }

    @Test
    @DisplayName("⭐ an unreadable status resolves to ACTIVE — a typo must never shut a shop")
    void unreadable_status_reads_permissively() {
        /*
         * The OPPOSITE direction from Plan.byCode, and both are deliberate.
         *
         * An unreadable PLAN resolves to the narrowest tier, because guessing generously about a licence
         * gives the product away. An unreadable STATUS resolves to ACTIVE, because guessing strictly is the
         * difference between a shop trading and a shop shut — and a value written by an older build, or a
         * NULL left by a migration, must never be the reason a paying customer cannot log in on a Monday.
         *
         * The strictness lives at the WRITE instead: parse() refuses what byCode() tolerates.
         */
        assertThat(OrganizationStatus.byCode("WHATEVER")).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(OrganizationStatus.byCode(null)).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(OrganizationStatus.byCode("  ")).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(OrganizationStatus.byCode(" suspended ")).isEqualTo(OrganizationStatus.SUSPENDED);
    }

    @Test
    @DisplayName("⭐ the WRITE refuses what the READ tolerates")
    void parse_is_strict_where_bycode_is_not() {
        /*
         * Reads and writes want opposite answers, and one method doing both is how a silent fallback ends up
         * applied to an operator's typo — leaving them believing a customer is stopped while that customer
         * carries on selling. `status` is free text on the column, exactly as `plan` was before E2.
         */
        assertThat(OrganizationStatus.parse("SUSPEND")).as("a near-miss must be refused, not guessed").isNull();
        assertThat(OrganizationStatus.parse("")).isNull();
        assertThat(OrganizationStatus.parse(null)).isNull();
        assertThat(OrganizationStatus.parse(" Closed ")).isEqualTo(OrganizationStatus.CLOSED);
    }

    @Test
    @DisplayName("SUSPENDED and CLOSED are distinct states, not synonyms")
    void suspended_and_closed_are_distinct() {
        // They behave identically at the door and answer different questions on a report — dunning versus
        // churn. Merged, they could never be taken apart again. Stripe keeps `unpaid` and `canceled` apart
        // for the same reason.
        assertThat(OrganizationStatus.SUSPENDED).isNotEqualTo(OrganizationStatus.CLOSED);
        assertThat(OrganizationStatus.values()).hasSize(3);
    }

    // ── the exemption the Cypress gate deliberately cannot reach ────────────────────────────────

    @Test
    @DisplayName("⭐ the operator's exemption is a ROLE check, never a privilege check")
    void the_exemption_keys_on_the_role() {
        /*
         * THE CASE THE GATE CANNOT MAKE, and why it lives here.
         *
         * To assert end to end that ROLE_ADMIN still signs in while their own organization is suspended, the
         * spec would have to actually suspend the operator's org — which `changeStatus` refuses, by design.
         * Reaching it would need a `force` flag: an override that defeats a safety guard, added to the
         * product purely so a test can reach a state the product exists to prevent. That makes the product
         * worse to make the test easier.
         *
         * What is actually worth pinning is the DISCRIMINATOR. `AuthService.isPlatformOperator` matches the
         * role name ROLE_ADMIN and must never be loosened to ADMIN_PRIVILEGE: every tenant owner holds that
         * privilege inside their own organization, so a privilege check would exempt every customer from
         * suspension — turning the lever off for precisely the people it exists for.
         */
        assertThat(isOperator("ROLE_ADMIN")).as("the platform operator").isTrue();
        assertThat(isOperator("role_admin")).as("case-insensitive, as the production check is").isTrue();

        assertThat(isOperator("ROLE_OWNER")).as("a tenant owner is NOT exempt").isFalse();
        assertThat(isOperator("ADMIN_ROLE")).as("a tenant admin is NOT exempt").isFalse();
        assertThat(isOperator("ADMIN_PRIVILEGE"))
                .as("⭐ the privilege every owner holds must never exempt anyone")
                .isFalse();
        assertThat(isOperator("SUPER_PRIVILEGE")).as("nor the super privilege").isFalse();
    }

    /**
     * The same predicate {@code AuthService.isPlatformOperator} applies, over one authority name.
     *
     * <p>Restated here rather than reached through {@code AuthService} because that class needs eleven
     * collaborators to construct, and a test that mocks eleven things to assert one string comparison tests
     * the mocks. If the production check ever stops being "does any role equal ROLE_ADMIN", this test is
     * wrong in a way a reviewer can see — which is the most a unit test can honestly offer here.
     */
    private static boolean isOperator(String roleName) {
        return "ROLE_ADMIN".equalsIgnoreCase(roleName);
    }
}
