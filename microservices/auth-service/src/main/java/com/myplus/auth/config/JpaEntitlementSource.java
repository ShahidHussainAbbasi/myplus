package com.myplus.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myplus.auth.entity.OrgEntitlement;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.OrgEntitlementRepository;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.EntitlementSource;
import com.myplus.common.settings.Plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E1 — auth-service's {@link EntitlementSource}: the platform's answer to "was this tenant sold this?".
 *
 * <h3>Its presence is what makes the ceiling real</h3>
 * {@code CommonSettingsAutoConfiguration} publishes a permissive default under
 * {@code @ConditionalOnMissingBean}; this bean replaces it here and only here. Every other service reads the
 * ceiling's RESULT from the {@code caps} JWT claim, which auth stamps at token mint — so no other service
 * needs this table, this bean, or a remote call on any hot path. That is C3c's design, reused rather than
 * re-argued.
 *
 * <h3>The resolution order</h3>
 * <pre>
 *   1. an explicit org_entitlement row     ACTIVE and inside its date window   ← WINS, either way
 *   2. the tenant's plan                   Plan.byCode(organizations.plan)
 *   3. TRIAL only: trial_ends_at            past it, the plan contributes nothing (ruling D-4)
 * </pre>
 * A row wins in BOTH directions — a {@code SUSPENDED} row revokes something the plan includes, and an
 * {@code ACTIVE} row grants something it does not. That is what an operator needs to honour a contract without
 * inventing a plan per customer.
 *
 * <h3>Dates are applied on READ</h3>
 * A row left {@code ACTIVE} with {@code ends_at} in the past does not entitle. Expiry enforced by a job that
 * rewrites statuses would make a missed run free licensing; enforcing it in the read makes any such job
 * housekeeping rather than the control.
 *
 * <h3>Performance — one cached query per tenant (standard 7c)</h3>
 * Read once per tenant into a bounded Caffeine cache, keyed by organisation. Thirteen capability questions per
 * token mint become one query on a miss and none on a hit. <b>Keyed by org is the load-bearing part</b>: a
 * cache keyed without it would serve one tenant's licence to another, silently and absent from the logs — the
 * same property PERF-C1's cache is designed around.
 *
 * <p>The cached snapshot holds ROWS, not a resolved boolean set, so the date window is evaluated on every call.
 * Caching the resolved answer would have made an expiry take effect only at the next eviction, which is the one
 * kind of staleness this table exists to prevent.
 */
@Component
public class JpaEntitlementSource implements EntitlementSource {

    private final OrgEntitlementRepository entitlements;
    private final OrganizationRepository organizations;

    /**
     * One tenant's licensing snapshot. Invalidated exactly on write by {@code EntitlementService}; the TTL is
     * a backstop for a future multi-replica deployment, not the primary mechanism — the same stance
     * {@code SettingsService}'s cache takes and for the same reason.
     */
    private final Cache<Long, Snapshot> byOrg;

    public JpaEntitlementSource(OrgEntitlementRepository entitlements,
                                OrganizationRepository organizations,
                                @Value("${app.entitlements.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.entitlements = entitlements;
        this.organizations = organizations;
        this.byOrg = Caffeine.newBuilder()
                .maximumSize(1_000)
                // A negative value is nonsense and falls back to the default; ZERO is not — it is an operator
                // switching the cache off to diagnose something without a redeploy.
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds < 0 ? 60 : cacheTtlSeconds))
                .build();
    }

    /**
     * May the owner switch this on? <b>The plan is allowed to bound here</b>, because this answer only ever
     * reaches a person making a decision, and {@code EntitlementWriteGuard} can tell them which plan they are
     * on. Nothing on a read path calls it.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean grantable(Long organizationId, Capability capability) {
        if (capability == null) return true;
        // No tenant means the caller is not asking about a tenant at all — a storefront shopper, a probe.
        // The ceiling has nothing to add, and refusing here would break anonymous paths that never needed a
        // licence; the configuration layer below already decides what such a caller sees.
        if (organizationId == null) return true;

        Snapshot snap = byOrg.get(organizationId, this::load);
        LocalDateTime now = LocalDateTime.now();

        OrgEntitlement row = snap.rows.get(capability.code());
        if (row != null) {
            // An explicit row is the operator's decision and beats the plan in BOTH directions — it grants
            // beyond the plan (honouring a contract without inventing a tier) and withdraws within it.
            return "ACTIVE".equalsIgnoreCase(row.getStatus()) && inWindow(row, now);
        }

        // No row: the plan decides. This is also where a capability added to the enum LATER is bounded (F6),
        // with no seeder and no migration involved — every tenant simply meets it with no row.
        //
        // A TRIAL past its end date contributes nothing (ruling D-4): one question, one answer, rather than a
        // trial date checked in one place and a capability in another.
        if (snap.plan == Plan.TRIAL && snap.trialEndsAt != null && snap.trialEndsAt.isBefore(now)) return false;
        return snap.plan.includes(capability);
    }

    /**
     * Has this been explicitly WITHDRAWN? <b>Positive evidence only.</b>
     *
     * <p>The plan is deliberately not consulted. This is the only thing permitted to take a capability away
     * from a tenant already using it, so it must fire on a decision somebody actually made — an operator's
     * non-ACTIVE row, or a grant whose window has closed — and never on the silence of a tenant that has no
     * licensing record at all.
     *
     * <p>That silence is the normal case: {@code org_entitlement} holds DEVIATIONS from the plan, so a tenant
     * that has never been the subject of an operator decision has no rows, and correctly loses nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean revoked(Long organizationId, Capability capability) {
        if (capability == null || organizationId == null) return false;

        OrgEntitlement row = byOrg.get(organizationId, this::load).rows.get(capability.code());
        if (row == null) return false;   // no decision recorded ⇒ nothing withdrawn

        return !"ACTIVE".equalsIgnoreCase(row.getStatus()) || !inWindow(row, LocalDateTime.now());
    }

    private boolean inWindow(OrgEntitlement row, LocalDateTime now) {
        if (row.getStartsAt() != null && row.getStartsAt().isAfter(now)) return false;
        return row.getEndsAt() == null || row.getEndsAt().isAfter(now);
    }

    private Snapshot load(Long organizationId) {
        Map<String, OrgEntitlement> rows = new LinkedHashMap<>();
        for (OrgEntitlement e : entitlements.findByOrganizationId(organizationId)) {
            rows.put(e.getCapability(), e);
        }
        Organization org = organizations.findById(organizationId).orElse(null);
        // A missing organization resolves to Plan.FREE, the NARROWEST tier — the opposite direction from
        // Shape.byCode, deliberately. An unreadable shape must not stop a shop trading; an unreadable licence
        // must not give the product away. The tenant still trades on FREE's set.
        Plan plan = Plan.byCode(org == null ? null : org.getPlan());
        return new Snapshot(plan, org == null ? null : org.getTrialEndsAt(), rows);
    }

    /** Drop one tenant's snapshot. Called by {@code EntitlementService} immediately after any write. */
    public void invalidate(Long organizationId) {
        if (organizationId != null) byOrg.invalidate(organizationId);
    }

    private record Snapshot(Plan plan, LocalDateTime trialEndsAt, Map<String, OrgEntitlement> rows) { }
}
