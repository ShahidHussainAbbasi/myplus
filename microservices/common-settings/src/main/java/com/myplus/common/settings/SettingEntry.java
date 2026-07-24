package com.myplus.common.settings;

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
 */
public record SettingEntry(String key, String label, String help, SettingType type,
                           String defaultValue, String group) {

    public enum SettingType { BOOL, INT, TEXT }

    public static SettingEntry bool(String key, String label, String help, boolean def, String group) {
        return new SettingEntry(key, label, help, SettingType.BOOL, Boolean.toString(def), group);
    }
}
