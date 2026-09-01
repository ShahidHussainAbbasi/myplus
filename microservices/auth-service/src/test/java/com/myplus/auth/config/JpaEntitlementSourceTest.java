package com.myplus.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myplus.auth.entity.OrgEntitlement;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.OrgEntitlementRepository;
import com.myplus.common.settings.Capability;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E1 — the entitlement resolver, where being wrong is silent and expensive.
 *
 * <p>Design: {@code microservices/docs/slices/e1-entitlement-ceiling.md}. The first case below is the one that
 * actually failed in the field: {@code capability-shapes.cy.js} reported every capability off for
 * {@code owner.mobile@}, because a legacy tenant on the {@code @Builder.Default} plan {@code FREE} was being
 * measured against that plan on a READ. It is first in the file for that reason.
 */
class JpaEntitlementSourceTest {

    private static final long ORG = 42L;

    private static Organization org(String plan, LocalDateTime trialEnds) {
        Organization o = new Organization();
        o.setId(ORG);
        o.setName("Test tenant");
        o.setPlan(plan);
        o.setTrialEndsAt(trialEnds);
        return o;
    }

    private static OrgEntitlement row(Capability c, String status, LocalDateTime endsAt) {
        return OrgEntitlement.builder()
                .organizationId(ORG).capability(c.code())
                .status(status).source("ADMIN_OVERRIDE").endsAt(endsAt)
                .build();
    }

    private static JpaEntitlementSource source(Organization organization, List<OrgEntitlement> rows) {
        OrgEntitlementRepository entitlements = mock(OrgEntitlementRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        when(entitlements.findByOrganizationId(any())).thenReturn(rows);
        when(organizations.findById(any())).thenReturn(Optional.ofNullable(organization));
        return new JpaEntitlementSource(entitlements, organizations, 60L);
    }

    // ── the regression that shipped ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ NO ROW means NOT REVOKED — a legacy tenant never loses a capability to its plan")
    void no_row_means_not_revoked() {
        /*
         * THE BUG THIS PINS. Every organization created before E1 carries plan "FREE", supplied by
         * @Builder.Default in getOrCreatePrimaryOrg — a value nothing had ever read for capability. Falling
         * through to the plan for a tenant with no entitlement rows therefore stripped ten capabilities from
         * every legacy tenant the moment the ceiling shipped, which is exactly what the gate reported.
         *
         * The deeper error was ONE question doing two jobs. "Is this tenant entitled?" — meaning a row else
         * the plan — is right for a WRITE and catastrophic for a READ. The fix was a second question, not a
         * wider default: `revoked` consults rows ONLY, so it cannot strip a tenant nobody has decided about.
         */
        JpaEntitlementSource src = source(org("FREE", null), List.of());

        for (Capability c : Capability.values()) {
            assertThat(src.revoked(ORG, c))
                    .as("%s — nothing was withdrawn, so nothing may be taken away", c.code())
                    .isFalse();
        }
        // And the commercial bound is still real on the WRITE side, where a person is told why.
        assertThat(src.grantable(ORG, Capability.BATCH_TRACKING)).as("FREE includes it").isTrue();
        assertThat(src.grantable(ORG, Capability.INSTALLMENTS)).as("FREE does not").isFalse();
    }

    @Test
    @DisplayName("the plan bounds what may be ENABLED, and never what is already on")
    void plan_bounds_only_the_write() {
        /*
         * The other half of the same rule, and F6's requirement: a capability added to the enum AFTER a tenant
         * was entitled is available on plan terms only. Without this the ceiling would leak a little on every
         * release. BONUS_SCHEMES is not in Plan.FREE, and this tenant has a row for something else — so it has
         * been through entitlement and is bounded.
         */
        JpaEntitlementSource src = source(org("FREE", null),
                List.of(row(Capability.BATCH_TRACKING, "ACTIVE", null)));

        assertThat(src.grantable(ORG, Capability.BATCH_TRACKING)).as("its own row").isTrue();
        assertThat(src.grantable(ORG, Capability.LOOSE_SELLING)).as("no row, but FREE includes it").isTrue();
        assertThat(src.grantable(ORG, Capability.BONUS_SCHEMES)).as("no row and FREE excludes it").isFalse();

        // ...and not one of them is REVOKED, because nobody withdrew anything. This is the pair that keeps
        // F1 closed without the read path ever consulting a plan.
        assertThat(src.revoked(ORG, Capability.BONUS_SCHEMES)).isFalse();
    }

    // ── a row is the operator's decision, in both directions ────────────────────────────────────

    @Test
    @DisplayName("a SUSPENDED row revokes a capability the plan includes")
    void suspended_row_revokes() {
        JpaEntitlementSource src = source(org("PRO", null),
                List.of(row(Capability.INSTALLMENTS, "SUSPENDED", null)));

        assertThat(src.revoked(ORG, Capability.INSTALLMENTS))
                .as("PRO includes it, but the operator withdrew it — positive evidence, so it subtracts")
                .isTrue();
        assertThat(src.grantable(ORG, Capability.INSTALLMENTS))
                .as("and it cannot be switched back on until the operator restores it")
                .isFalse();
    }

    @Test
    @DisplayName("an ACTIVE row grants a capability the plan excludes")
    void active_row_grants_beyond_the_plan() {
        // What an operator needs to honour a contract without inventing a plan per customer.
        JpaEntitlementSource src = source(org("FREE", null),
                List.of(row(Capability.INSTALLMENTS, "ACTIVE", null)));

        assertThat(src.grantable(ORG, Capability.INSTALLMENTS)).isTrue();
        assertThat(src.revoked(ORG, Capability.INSTALLMENTS)).isFalse();
    }

    // ── dates are applied on READ ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ACTIVE row whose end date has passed does not entitle")
    void expired_row_does_not_entitle() {
        /*
         * Expiry enforced by a job that rewrites statuses would make a missed run free licensing. Applying the
         * window on read makes any such job housekeeping rather than the control.
         */
        JpaEntitlementSource src = source(org("FREE", null),
                List.of(row(Capability.INSTALLMENTS, "ACTIVE", LocalDateTime.now().minusDays(1))));

        assertThat(src.grantable(ORG, Capability.INSTALLMENTS)).as("cannot be re-enabled").isFalse();
        assertThat(src.revoked(ORG, Capability.INSTALLMENTS)).as("and it is withdrawn now").isTrue();
    }

    @Test
    @DisplayName("a TRIAL past its end date contributes nothing, but an explicit row survives it")
    void expired_trial_falls_away() {
        // Ruling D-4: the trial date and the capability are one question with one answer.
        JpaEntitlementSource expired = source(org("TRIAL", LocalDateTime.now().minusDays(1)),
                List.of(row(Capability.BATCH_TRACKING, "ACTIVE", null)));

        assertThat(expired.grantable(ORG, Capability.INSTALLMENTS))
                .as("no row, and the trial has lapsed, so it may not be switched on")
                .isFalse();
        assertThat(expired.revoked(ORG, Capability.INSTALLMENTS))
                .as("⭐ but a lapsed trial does not WITHDRAW what the tenant is already using — "
                        + "that is a renewal conversation, not a switch that flips mid-trade")
                .isFalse();
        assertThat(expired.grantable(ORG, Capability.BATCH_TRACKING))
                .as("an explicit grant is not a trial benefit and outlives it")
                .isTrue();

        JpaEntitlementSource live = source(org("TRIAL", LocalDateTime.now().plusDays(7)),
                List.of(row(Capability.BATCH_TRACKING, "ACTIVE", null)));
        assertThat(live.grantable(ORG, Capability.INSTALLMENTS))
                .as("a live trial includes everything")
                .isTrue();
    }

    // ── the edges the resolver must not throw on ────────────────────────────────────────────────

    @Test
    @DisplayName("a null org or capability is not a refusal")
    void nulls_do_not_refuse() {
        /*
         * A storefront shopper or a health probe has no tenant. The CEILING has nothing to say about a caller
         * that is not asking about a tenant, and answering false would refuse anonymous paths that never
         * needed a licence — the configuration layer below already decides what such a caller sees.
         */
        JpaEntitlementSource src = source(org("FREE", null), List.of());

        assertThat(src.grantable(null, Capability.INSTALLMENTS)).isTrue();
        assertThat(src.grantable(ORG, null)).isTrue();
        assertThat(src.revoked(null, Capability.INSTALLMENTS)).isFalse();
        assertThat(src.revoked(ORG, null)).isFalse();
    }

    @Test
    @DisplayName("a missing organization with rows present falls back to the NARROWEST plan")
    void missing_organization_falls_back_narrow() {
        /*
         * Opposite direction from Shape.byCode, deliberately. An unreadable shape must not stop a shop
         * trading; an unreadable LICENCE must not give the product away. The tenant has rows, so it is
         * bounded — and what bounds it is FREE.
         */
        JpaEntitlementSource src = source(null, List.of(row(Capability.BATCH_TRACKING, "ACTIVE", null)));

        assertThat(src.grantable(ORG, Capability.BATCH_TRACKING)).as("its own row still stands").isTrue();
        assertThat(src.grantable(ORG, Capability.BONUS_SCHEMES)).as("and FREE bounds the rest").isFalse();
        assertThat(src.revoked(ORG, Capability.BONUS_SCHEMES)).as("but nothing is withdrawn").isFalse();
    }
}
