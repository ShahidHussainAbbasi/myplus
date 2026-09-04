package com.myplus.auth.config;

import org.springframework.stereotype.Component;

import com.myplus.auth.service.ControlPlaneAuditService;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.SettingWriteListener;

import lombok.RequiredArgsConstructor;

/**
 * E4 — records a tenant switching one of its own capabilities on or off.
 *
 * <h3>Why a listener and not a call inside the controller</h3>
 * A capability reaches {@code org_setting} by more than one door — the Configuration screen, the settings
 * proxy the monolith routes by key prefix (C3c), and any future admin path. Hooking the controller would
 * record whichever door the author was thinking about. {@link SettingWriteListener} sits at the single point
 * every write passes through, which is the same argument E1 made for putting its ceiling in
 * {@code SettingsService.set} rather than in the screens.
 *
 * <h3>⚠ Only {@code org.cap.*}</h3>
 * Letterhead, tax defaults, locale and the rest are ordinary tenant configuration, not control-plane activity.
 * A listener recording all of them would bury five interesting events a month under a thousand, and the
 * Activity panel would become the thing nobody reads.
 *
 * <p>{@code org.shape} is deliberately absent too, and not by oversight: a business-type change is recorded by
 * {@code OrganizationAdminService.applyShape} through {@code shapeAction}, because re-applying a preset is
 * more than one row and the event has to carry what it cleared. Recording it here as well would produce two
 * events for one change, which is exactly the duplication ruling D-3 was careful to avoid.
 *
 * <h3>Why this cannot record a refused write</h3>
 * {@code SettingsService.set} runs its guards — including E1's entitlement ceiling — BEFORE the upsert and
 * inside the caller's transaction, and calls listeners only after it has completed. A refusal therefore never
 * reaches here. That ordering is the whole reason the trail can be trusted: a record of changes that did not
 * happen is worse than no record, because nothing downstream can tell which rows are real.
 */
@Component
@RequiredArgsConstructor
public class CapabilityToggleAuditListener implements SettingWriteListener {

    private static final String CAP_PREFIX = "org.cap.";

    private final ControlPlaneAuditService audit;

    @Override
    public void applied(Long organizationId, String key, String before, String after) {
        if (key == null || !key.startsWith(CAP_PREFIX)) return;

        Capability capability = Capability.byCode(key.substring(CAP_PREFIX.length()));
        // A key that is in the catalog but not in the enum cannot happen today. If it ever does, silence is
        // right: an audit trail is not the place to discover a configuration bug, and an event naming a
        // capability nobody can look up tells a reader nothing.
        if (capability == null) return;

        audit.tenantAction(
                ControlPlaneAuditService.CAPABILITY_TOGGLE,
                ControlPlaneAuditService.ENTITY_CAPABILITY,
                capability.code(),
                /*
                 * The raw previous override, which SettingsService read before overwriting it — null when the
                 * tenant had none. Rendered as "preset" rather than left blank because that IS the prior
                 * state: the shape's default was deciding, and "there was no override" is information an
                 * operator reading this trail actually needs.
                 */
                before == null ? "preset" : before,
                after,
                capability.label());
    }
}
