package com.myplus.common.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.myplus.common.security.CurrentUser;

/**
 * C1 — the one place that answers "may this tenant do this?".
 *
 * <h3>The gap this closes</h3>
 * Before this, a capability was hidden by a menu and nothing else. The API answered anyone who asked, so the
 * control was a suggestion: any caller who knew the URL got the behaviour whether the tenant had it or not.
 * <b>Hiding a menu is not security.</b> That was the only item in the capability review that was a defect
 * rather than an improvement, and it is what {@code assertEnabled} exists for.
 *
 * <h3>Reading is cheap on purpose</h3>
 * It delegates to {@link SettingsService}, which holds a tenant's whole override map behind a bounded
 * per-tenant Caffeine cache (PERF-C1). So a capability check on a hot path costs a map lookup, not a query —
 * which matters because the intended use is a guard on every write, not an occasional screen decision.
 *
 * <h3>Fail OPEN for visibility, CLOSED for money</h3>
 * A capability that cannot be resolved — no tenant on the request, settings unavailable — resolves to
 * {@code true}. A tenant losing a screen it used yesterday is a support call; the alternative default would
 * turn any settings hiccup into an outage. Callers guarding something that touches <b>stock, ledger or tax</b>
 * must not rely on that: they use {@link #assertEnabled}, which refuses when it cannot prove the tenant is
 * allowed.
 */
@Service
public class CapabilityService {

    private final SettingsService settings;

    public CapabilityService(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * Is this capability on for the CALLER's tenant?
     *
     * <p>Fails open — see the class javadoc. Use for deciding what to render.
     */
    public boolean isEnabled(Capability capability) {
        return isEnabledFor(CurrentUser.organizationId(), capability);
    }

    /**
     * Is it on for an explicitly named tenant?
     *
     * <p>Exists for the same reason {@code SettingsService.effectiveFor} does: not every reader of a tenant
     * policy is an authenticated member of that tenant. A background scanner, or a service acting for a
     * storefront shopper, knows the org from the row it is working on rather than from a security context.
     */
    public boolean isEnabledFor(Long organizationId, Capability capability) {
        if (capability == null) return true;              // nothing asked for, nothing to refuse
        try {
            // getBoolFor already applies the catalog default (true) when a tenant has no override.
            return settings.getBoolFor(organizationId, capability.settingKey());
        } catch (RuntimeException settingsUnavailable) {
            // Fail OPEN: a settings outage must not take away screens that worked a minute ago.
            return true;
        }
    }

    /**
     * Refuse unless this capability is on. <b>The server-side half of the control.</b>
     *
     * <p>Fails CLOSED, unlike {@link #isEnabled}: a caller reaching this has decided the operation is one that
     * must not happen without the capability — recording a serial against stock, dispensing against a
     * prescription, posting a field collection. For those, "we could not tell" has to mean no.
     *
     * <p><b>Worded as a refusal of the ACTION, never as a report on the tenant's configuration.</b> A message
     * naming which capabilities an org has is an information disclosure to anyone probing endpoints; this says
     * what cannot be done and stops there. Same rule as the anti-IDOR reads, where "not yours" and "not there"
     * are deliberately indistinguishable.
     *
     * @throws com.myplus.common.web.exception.ValidationException when the capability is off
     */
    public void assertEnabled(Capability capability) {
        if (capability == null) return;
        Long org = CurrentUser.organizationId();
        // No tenant identity => cannot prove the capability => refuse. This is the CLOSED half.
        boolean allowed = org != null && settings.getBoolFor(org, capability.settingKey());
        if (!allowed) {
            throw new com.myplus.common.web.exception.ValidationException(
                    "This is not switched on for your business.");
        }
    }

    /**
     * Every capability and whether it is on, for the caller's tenant — the payload a screen needs.
     *
     * <p>One call rather than one per capability: the dashboard decides visibility for ~31 sections at load,
     * and a round trip each would be its own performance problem. Keyed by short code, because that is what
     * {@code [data-capability]} carries in the markup.
     */
    public Map<String, Boolean> enabledMap() {
        Long org = CurrentUser.organizationId();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Capability c : Capability.values()) {
            out.put(c.code(), isEnabledFor(org, c));
        }
        return out;
    }
}
