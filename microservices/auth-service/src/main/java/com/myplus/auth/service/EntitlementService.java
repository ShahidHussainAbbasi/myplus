package com.myplus.auth.service;

import com.myplus.auth.config.JpaEntitlementSource;
import com.myplus.auth.entity.OrgEntitlement;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.OrgEntitlementRepository;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.Plan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E1 — the platform operator's view of, and control over, one tenant's entitlements.
 *
 * <h3>Who calls this</h3>
 * Only {@code EntitlementAdminController}, gated on the platform {@code ROLE_ADMIN}. Deliberately not on
 * {@code ADMIN_PRIVILEGE}: company owners hold the super privilege set inside their own tenant, so a privilege
 * gate would let any owner grant themselves entitlements — the same hole E1 closes, reopened one layer up. The
 * reasoning is already recorded on {@code provision-tenant}, and it is repeated here because the consequence is
 * worse: that endpoint creates a tenant, this one prices it.
 *
 * <h3>The write invalidates the resolver's cache immediately</h3>
 * Otherwise an operator would grant a capability, watch the tenant still be refused, and have no way to tell a
 * broken grant from a cached one. The tenant's own sessions still catch up at their next token refresh — within
 * the 15-minute access-token lifetime — which is the documented cost of the claim-based design (ruling D-1).
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final OrgEntitlementRepository entitlements;
    private final OrganizationRepository organizations;
    private final JpaEntitlementSource source;
    /**
     * ONB-1 — so the operator can see what the tenant ACTUALLY has on, not only what it may have.
     *
     * <p>{@code grantable} and {@code revoked} are platform facts; {@code enabled} is the resolver's answer —
     * the same one the tenant's token is minted from. Without it an operator cannot tell "we withdrew this"
     * from "they switched it off themselves", which are opposite problems with identical symptoms.
     */
    private final com.myplus.common.settings.CapabilityService capabilities;

    /**
     * Every capability, with what the tenant is entitled to and why — the operator screen's payload (E2).
     *
     * <p>Returns the whole capability set rather than only the rows that exist, because the interesting cases
     * are the absences: a capability with no row is on plan terms, and an operator needs to see that to know
     * whether granting it is a change at all.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> forOrganization(Long organizationId) {
        Organization org = organizations.findById(organizationId).orElse(null);
        Plan plan = Plan.byCode(org == null ? null : org.getPlan());

        Map<String, OrgEntitlement> rows = new LinkedHashMap<>();
        for (OrgEntitlement e : entitlements.findByOrganizationId(organizationId)) {
            rows.put(e.getCapability(), e);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Capability c : Capability.values()) {
            OrgEntitlement row = rows.get(c.code());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capability", c.code());
            m.put("label", c.label());
            m.put("help", c.help());
            m.put("inPlan", plan.includes(c));
            m.put("status", row == null ? null : row.getStatus());
            m.put("source", row == null ? "PLAN" : row.getSource());
            m.put("startsAt", row == null || row.getStartsAt() == null ? null : row.getStartsAt().toString());
            m.put("endsAt", row == null || row.getEndsAt() == null ? null : row.getEndsAt().toString());
            m.put("reason", row == null ? null : row.getReason());
            // BOTH answers, computed by the SAME resolver enforcement uses. Recomputing them here from plan +
            // row would be a second implementation of the resolution order, and a screen that disagrees with
            // enforcement is worse than one that shows less.
            //
            // They are genuinely different facts and an operator needs both: `grantable` is whether the owner
            // could switch it on, `revoked` is whether the platform has taken it away from a tenant already
            // using it. Only the second subtracts — see EntitlementSource's javadoc.
            m.put("grantable", source.grantable(organizationId, c));
            m.put("revoked", source.revoked(organizationId, c));
            // The effective answer, through the SAME resolver enforcement uses — never recomputed here from
            // plan + row, which would be a second implementation of the resolution order.
            m.put("enabled", capabilities.isEnabledFor(organizationId, c));
            out.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationId", organizationId);
        result.put("organizationName", org == null ? null : org.getName());
        result.put("plan", plan.code());
        // E3 — the console's status control reads this. Raw rather than through OrganizationStatus.byCode,
        // because byCode falls back to ACTIVE for an unreadable value and the OPERATOR is exactly the person
        // who needs to see that the column holds something the platform does not understand.
        result.put("status", org == null ? null : org.getStatus());
        // ONB-1 — the business type, so the detail screen can offer to change it.
        result.put("shape", capabilities.shapeFor(organizationId).code());
        result.put("trialEndsAt", org == null || org.getTrialEndsAt() == null ? null : org.getTrialEndsAt().toString());
        result.put("capabilities", out);
        return result;
    }

    /**
     * Grant, revoke or time-box one capability for one tenant. Upsert — one row per (org, capability).
     *
     * <p>Validates the capability against the enum rather than storing whatever arrives: a typo would otherwise
     * become a row that entitles nothing, looks correct on the operator screen, and is discovered by the
     * customer.
     *
     * @param status ACTIVE · SUSPENDED · EXPIRED
     */
    @Transactional
    public void set(Long organizationId, String capabilityCode, String status, String source,
                    LocalDateTime startsAt, LocalDateTime endsAt, String reason, Long grantedBy) {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        /*
         * E2 — a REASON is required, by the API and not merely by the form.
         *
         * A UI-only requirement is not a requirement: the endpoint is reachable without the screen, and the
         * half of the callers that skip it are the ones nobody remembers writing. This is also what makes
         * E4 a listener rather than a retrofit — an audit trail of unexplained revocations answers "who"
         * and "when" and not the only question anybody ever asks, which is "why".
         *
         * Enforced on EVERY status, grants included. "Why does this customer have a capability their plan
         * excludes?" is exactly as expensive to answer six months later as "why did they lose one".
         */
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required for an entitlement change.");
        Capability capability = Capability.byCode(capabilityCode);
        if (capability == null) throw new IllegalArgumentException("Unknown capability: " + capabilityCode);
        String st = status == null ? "ACTIVE" : status.trim().toUpperCase();
        if (!List.of("ACTIVE", "SUSPENDED", "EXPIRED").contains(st))
            throw new IllegalArgumentException("Unknown status: " + status);

        OrgEntitlement row = entitlements
                .findByOrganizationIdAndCapability(organizationId, capability.code())
                .orElseGet(() -> OrgEntitlement.builder()
                        .organizationId(organizationId)
                        .capability(capability.code())
                        .build());
        row.setStatus(st);
        row.setSource(source == null || source.isBlank() ? "ADMIN_OVERRIDE" : source.trim().toUpperCase());
        row.setStartsAt(startsAt);
        row.setEndsAt(endsAt);
        row.setReason(reason);
        row.setGrantedBy(grantedBy);
        entitlements.save(row);

        // Exactly on write, like SettingsService's eviction and for the same reason: a TTL as the primary
        // mechanism is at once too slow for the operator who just made a change and watched nothing happen,
        // and too fast for the overwhelming majority of reads where nothing changed.
        this.source.invalidate(organizationId);
    }
}
