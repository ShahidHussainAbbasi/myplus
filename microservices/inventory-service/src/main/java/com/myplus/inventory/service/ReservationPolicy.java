package com.myplus.inventory.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingsService;
import com.myplus.inventory.config.InventorySettingsCatalog;

/**
 * OMS O5a — how long a stock hold lives, for THIS tenant.
 *
 * <h3>Why the org is a parameter</h3>
 * The sweeper runs on a schedule with <b>no security context at all</b>, so {@code CurrentUser.organizationId()}
 * is null there. Resolving the TTL ambiently would give every tenant the platform default while appearing to
 * work in an authenticated test — the exact failure O3 shipped with on the anonymous storefront path. Every
 * method here therefore names its tenant, and there is no overload that does not.
 *
 * <h3>Zero means never</h3>
 * A merchant who would rather investigate a stuck hold than have it vanish sets 0. It does not mean "expire
 * immediately": a threshold whose zero value silently means "always" is how O3's free-delivery threshold would
 * have made every order ship free.
 */
@Component
public class ReservationPolicy {

    /**
     * REQUIRED. common-settings only registers a {@code SettingsService} when the service supplies a
     * {@link com.myplus.common.settings.SettingsStore} bean, and an optional injection would turn a missing
     * store into "every tenant silently keeps the default TTL" instead of a startup failure — which is exactly
     * how O3 shipped inert. A service that means to be configurable must refuse to start unconfigurable.
     */
    @Autowired
    private SettingsService settingsService;

    /** Configured hold length in minutes for {@code org}; {@code 0} disables expiry. Never negative. */
    public int holdMinutes(Long org) {
        int configured = settingsService == null
                ? InventorySettingsCatalog.DEFAULT_HOLD_MINUTES
                : settingsService.getIntFor(org, InventorySettingsCatalog.HOLD_MINUTES,
                        InventorySettingsCatalog.DEFAULT_HOLD_MINUTES);
        // A negative TTL would mean "already expired at the moment of reserving", i.e. every sale racing its own
        // sweeper. Treat it as unset rather than as an instruction.
        return configured < 0 ? InventorySettingsCatalog.DEFAULT_HOLD_MINUTES : configured;
    }

    /**
     * The deadline to stamp on a hold taken at {@code from}, or {@code null} when this tenant has switched
     * expiry off. Null is the honest representation of "no deadline" — a far-future date would be a lie the
     * sweeper's query could not distinguish from a real one.
     */
    public LocalDateTime expiryFor(Long org, LocalDateTime from) {
        int minutes = holdMinutes(org);
        if (minutes == 0) return null;
        return (from == null ? LocalDateTime.now() : from).plusMinutes(minutes);
    }

    /**
     * Has this deadline passed at {@code now}?
     *
     * <p>Strictly after, so a 30-minute hold lasts the full 30 minutes; a hold exactly at its deadline is still
     * live. Null (expiry disabled, or a pre-V6 row) is never expired — those are dealt with deliberately through
     * the manual sweep, not swept up automatically by a migration's side effect.
     */
    public boolean isExpired(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && now != null && now.isAfter(expiresAt);
    }
}
