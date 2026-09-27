package com.myplus.business_service.config;

import com.myplus.common.settings.SettingWriteGuard;

import org.springframework.stereotype.Component;

/**
 * SET-CERT F1 (owner ruling 2026-09-26) — "Charge more on terms than for cash" cannot be switched ON yet.
 *
 * <p>The setting has always said "Off, and not yet available": a markup is finance income, not sales, and needs its
 * own account before it can be booked correctly. Nothing reads it, so a switch that could be ticked was a control
 * that did nothing. Refusing the write here — the platform's key-scoped extension point, like
 * {@link CutoverDateGuard} — also makes the Configuration screen show the row LOCKED with this sentence as its reason,
 * because the screen asks the same guards whether "true" would be accepted. One rule, both surfaces.
 *
 * <p>Switching it OFF is always allowed (a tenant that somehow holds "true" can clear it).
 */
@Component
public class InstallmentMarkupGuard implements SettingWriteGuard {

    static final String KEY = "pos.installment.markupEnabled";

    @Override
    public void check(Long organizationId, String key, String value) {
        if (!KEY.equals(key)) return;
        if (value == null || !"true".equalsIgnoreCase(value.trim())) return;
        throw new IllegalArgumentException(
                "Charging more on terms is not available yet: a markup is finance income and needs its own account "
                        + "before it can be booked. Until then, price the item at its installment price.");
    }
}
