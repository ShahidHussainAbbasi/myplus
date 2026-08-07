package com.myplus.common.settings;

import java.util.List;

/**
 * One configurable policy in a service's settings catalog — the source of truth for WHAT an owner may set.
 * The Configuration screen renders itself from these; the per-tenant store holds only overrides.
 *
 * @param key          stable id, e.g. "edu.guardian.branchScoped" (also the storage key)
 * @param label        human label for the Configuration screen
 * @param help         one-line explanation of on vs off
 * @param type         how to render/interpret the value
 * @param defaultValue the value when a tenant has no override (as a String, interpreted per {@code type})
 * @param group        UI section heading, e.g. "Branch policy"
 * @param options      for {@link SettingType#SELECT}, the allowed value/label pairs; empty otherwise
 */
public record SettingEntry(String key, String label, String help, SettingType type,
                           String defaultValue, String group, List<Option> options) {

    /**
     * MONEY is distinct from INT because minor units matter: a delivery fee of 5.50 is not expressible as a
     * whole number, and rendering it with INT's spinner would silently forbid the decimal. Read with
     * {@code SettingsService.getDecimal}; rendered by settings-form.js as a decimal-capable number input.
     */
    public enum SettingType { BOOL, INT, TEXT, SELECT, MONEY }

    /**
     * One choice in a SELECT setting.
     *
     * @param value what is stored (a stable code — never the label, which is translated and would
     *              change the stored value the moment someone switches language)
     * @param label what the owner reads on the Configuration screen
     */
    public record Option(String value, String label) { }

    /** Non-SELECT entries carry no options — keeps the existing 6-arg call sites working. */
    public SettingEntry(String key, String label, String help, SettingType type,
                        String defaultValue, String group) {
        this(key, label, help, type, defaultValue, group, List.of());
    }

    public static SettingEntry bool(String key, String label, String help, boolean def, String group) {
        return new SettingEntry(key, label, help, SettingType.BOOL, Boolean.toString(def), group);
    }

    public static SettingEntry select(String key, String label, String help, String def,
                                      String group, List<Option> options) {
        return new SettingEntry(key, label, help, SettingType.SELECT, def, group, options);
    }

    /**
     * A whole-number policy — a threshold, a count, a percentage.
     *
     * <p>Added for education slice 1.6 (promotion pass mark, exam attendance minimum). {@link
     * SettingType#INT} was already in this enum and {@code settings-form.js} already renders it as a number
     * input; only this factory and {@link SettingsService#getInt(String)} were missing, so an INT setting
     * could be declared but never conveniently read.
     *
     * <p>Deliberately an extension of this port rather than a second mechanism: a separate integer-settings
     * path would be a duplicate implementation of a capability that already exists.
     */
    public static SettingEntry intOf(String key, String label, String help, int def, String group) {
        return new SettingEntry(key, label, help, SettingType.INT, Integer.toString(def), group);
    }

    /**
     * A free-text policy — a name, an address line, a printed label.
     *
     * <p>Added for B2B Phase 3g, where the document letterhead (business name, address, licence, currency
     * wording) is owner-supplied text rather than a toggle. {@link SettingType#TEXT} was already in this
     * enum and {@code settings-form.js} already renders it as a text input; only this factory and {@link
     * SettingsService#getText(String)} were missing, so a TEXT setting could be declared but not
     * conveniently created or read — the same gap {@code intOf} closed for INT.
     *
     * <p>Deliberately an extension of this port rather than a second mechanism: a separate store for
     * "document text" would be a duplicate implementation of the per-tenant override that already exists.
     * Note the storage column is {@code VARCHAR(500)} — this is for short labels, never a document body.
     */
    public static SettingEntry text(String key, String label, String help, String def, String group) {
        return new SettingEntry(key, label, help, SettingType.TEXT, def == null ? "" : def, group);
    }

    /**
     * A money / decimal setting — a delivery fee, a free-shipping threshold, a discount percentage.
     *
     * <p>Rendered as {@link SettingType#MONEY} so the Configuration screen can offer a decimal input rather
     * than the whole-number field {@code INT} implies; read back with {@code settingsService.getDecimal}.
     * Introduced with OMS O3 once a second consumer needed it (see that method's note).
     */
    public static SettingEntry money(String key, String label, String help, String def, String group) {
        return new SettingEntry(key, label, help, SettingType.MONEY, def == null ? "0" : def, group);
    }
}
