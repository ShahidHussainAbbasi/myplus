package com.myplus.auth.config;

import com.myplus.common.settings.Capability;
import com.myplus.common.settings.EntitlementSource;
import com.myplus.common.settings.SettingWriteGuard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * E1 — refuses a capability an org was never sold, at the write.
 *
 * <h3>The hole</h3>
 * {@code SettingsService.set} checked that a key was in the catalog and nothing else, and {@code org.cap.*}
 * keys are in the catalog. {@code SettingsController.save} gates on {@code ROLE_OWNER or ADMIN_PRIVILEGE},
 * which every tenant owner holds inside their own org. So an owner could POST
 * {@code org.cap.installments=true} and hold a paid capability for nothing.
 *
 * <h3>Only the ENABLING direction is guarded</h3>
 * Switching a capability OFF is always allowed, even for a tenant that is no longer entitled. C6 established
 * this rule for per-product policies and it applies unchanged here: if withdrawing an entitlement also froze
 * the switch, a tenant would be left with a policy it can neither use nor clear, and the only way back would be
 * a DBA. The ceiling exists to stop unearned capability, not to trap a tenant in the last state it happened to
 * be in.
 *
 * <h3>Why this is not the only enforcement</h3>
 * It is the half that produces a good MESSAGE. The half that produces the RIGHT ANSWER is the ceiling inside
 * {@code CapabilityService.resolve}, which every {@code assertEnabled}, every {@code [data-capability]} and the
 * JWT {@code caps} claim already read. If this guard were ever unwired, an unentitled write would be accepted
 * and then have no effect — wrong, but visibly wrong, rather than a silent grant.
 *
 * <h3>The message</h3>
 * Names the PLAN, which is the tenant's own to know, and never the settings key: a message that describes the
 * configuration namespace is an information disclosure to anyone probing endpoints. Same rule the anti-IDOR
 * reads follow, applied to configuration.
 */
@Component
@RequiredArgsConstructor
public class EntitlementWriteGuard implements SettingWriteGuard {

    private final EntitlementSource entitlements;

    @Override
    public void check(Long organizationId, String key, String value) {
        Capability capability = capabilityOf(key);
        if (capability == null) return;                 // not a capability write — nothing to bound
        if (!isEnabling(value)) return;                 // turning it OFF is always allowed; see the javadoc
        // `grantable`, not `revoked`: THIS is where the plan is allowed to bound, because a refusal here
        // reaches a person making a decision and can explain itself. The read path deliberately asks the
        // other question — see EntitlementSource's javadoc for why the two must not be one.
        if (entitlements.grantable(organizationId, capability)) return;

        throw new IllegalArgumentException(
                "\"" + capability.label() + "\" is not included in your current plan. "
                        + "Contact MaxTheService to add it.");
    }

    /**
     * The capability a settings key names, or null when the key is not one.
     *
     * <p>Goes through {@code Capability.byCode} rather than matching the prefix and trusting the remainder:
     * {@code org.cap.} is a RESERVED namespace, so a key inside it that no capability answers to is a mistake
     * rather than a feature — and the right response to a mistake here is to let the catalog check that already
     * runs decide, not to refuse on a guess.
     */
    private Capability capabilityOf(String key) {
        String prefix = "org.cap.";
        if (key == null || !key.startsWith(prefix)) return null;
        return Capability.byCode(key.substring(prefix.length()));
    }

    /**
     * Is this write turning the capability ON?
     *
     * <p>Anything that is not literally {@code true} counts as off. A blank value is how the settings API
     * clears an override, and a cleared override falls back to the shape preset — which the READ ceiling
     * bounds anyway, so there is nothing here that a permissive reading could leak.
     */
    private boolean isEnabling(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }
}
