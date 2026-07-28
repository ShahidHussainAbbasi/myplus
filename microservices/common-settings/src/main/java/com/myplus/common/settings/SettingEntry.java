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

    public enum SettingType { BOOL, INT, TEXT, SELECT }

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
}
