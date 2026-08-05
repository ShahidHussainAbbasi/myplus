package com.myplus.common.settings;

import com.myplus.common.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared settings engine. Aggregates every service's {@link SettingsCatalogProvider}s into one catalog,
 * and reads/writes per-tenant overrides through the service's {@link SettingsStore}. Behaviour code calls
 * {@link #getBool(String)} — override if set, else the catalog default. All scoped to the caller's org.
 *
 * This class is registered by {@code CommonSettingsAutoConfiguration} (via @Import), so a consuming service
 * only needs a dependency + one {@link SettingsStore} bean + its catalog provider(s).
 */
@Service
public class SettingsService {

    private final SettingsStore store;
    private final Map<String, SettingEntry> catalog = new LinkedHashMap<>();

    public SettingsService(SettingsStore store, List<SettingsCatalogProvider> providers) {
        this.store = store;
        if (providers != null)
            for (SettingsCatalogProvider p : providers)
                for (SettingEntry e : p.entries())
                    catalog.putIfAbsent(e.key(), e);   // first registration wins; keys are globally unique
    }

    /** Effective boolean for the caller's org (override else catalog default; false if key unknown). */
    public boolean getBool(String key) {
        return "true".equalsIgnoreCase(effective(key));
    }

    /**
     * Effective whole number for the caller's org (override else catalog default).
     *
     * <p>Returns {@code fallback} when the key is unknown OR the stored value is not a number. A malformed
     * override must not throw: these are read on behaviour paths (a promotion pass mark, an attendance
     * threshold), and a settings typo bringing down the operation that reads it would be a worse failure
     * than quietly using the caller's stated default. The caller passes the fallback so the choice is
     * visible at the call site rather than buried here.
     */
    public int getInt(String key, int fallback) {
        String v = effective(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Effective value of a SELECT setting, lower-cased and validated against a known set.
     *
     * <p>Returns {@code fallback} when the key is unknown, unset, or holds a value outside {@code allowed}.
     * That last case is the point: a policy setting like {@code pos.sale.marginPolicy} drives a branch, and
     * an unrecognised value must resolve to the caller's stated safe default rather than silently falling
     * through every branch to "do nothing". Standard C3 — a safety flag fails ON.
     *
     * <p>An extension of this port rather than a second mechanism, for the same reason {@code getInt} is:
     * SELECT already exists in {@link SettingEntry.SettingType} and {@code settings-form.js} already
     * renders it; only a typed reader was missing.
     *
     * @param allowed the valid values, lower-case; {@code fallback} must be one of them
     */
    public String getChoice(String key, java.util.Set<String> allowed, String fallback) {
        String v = effective(key);
        if (v == null || v.isBlank()) return fallback;
        String norm = v.trim().toLowerCase(java.util.Locale.ROOT);
        return (allowed != null && allowed.contains(norm)) ? norm : fallback;
    }

    /**
     * A TEXT setting's effective value, or {@code null} when it is unknown, unset or blank.
     *
     * <p>Blank collapses to null on purpose: these back optional printed fields (a licence number, a second
     * address line), and the caller's question is always "is there a value to print?". Returning {@code ""}
     * would make every call site write the same emptiness check, and one of them would eventually forget and
     * print an empty label with a colon after it.
     */
    public String getText(String key) {
        String v = effective(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** Effective raw value for the caller's org (override else catalog default; null if key unknown). */
    public String effective(String key) {
        Long org = CurrentUser.organizationId();
        if (org != null) {
            String v = store.find(org, key).orElse(null);
            if (v != null) return v;
        }
        SettingEntry e = catalog.get(key);
        return e == null ? null : e.defaultValue();
    }

    /** The whole catalog with each entry's effective value + whether it is an org override — feeds the UI. */
    public List<Map<String, Object>> catalogForOrg() {
        Long org = CurrentUser.organizationId();
        Map<String, String> overrides = new LinkedHashMap<>();
        if (org != null)
            for (SettingsStore.Stored s : store.findAll(org)) overrides.put(s.key(), s.value());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingEntry e : catalog.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("label", e.label());
            m.put("help", e.help());
            m.put("type", e.type().name());
            m.put("group", e.group());
            m.put("value", overrides.getOrDefault(e.key(), e.defaultValue()));
            m.put("isDefault", !overrides.containsKey(e.key()));
            // The catalog IS the source of truth for what an owner may set, so the screen must receive the
            // choices — settings-form.js renders `(it.options || [])`, and without this every SELECT
            // rendered as an EMPTY dropdown: the policy existed, defaulted correctly and was enforced, but
            // could not be changed anywhere except the API. Emitted for all types (empty for non-SELECT),
            // because a client deciding by type is a second place for the contract to drift.
            m.put("options", e.options());
            // The declared default, distinct from `value` (the effective one). Without it a caller cannot
            // tell "warn because that is the default" from "warn because someone set it", and any test of
            // the default has to depend on no other actor having changed it.
            m.put("defaultValue", e.defaultValue());
            out.add(m);
        }
        return out;
    }

    /** Upsert an override for the caller's org. Rejects keys not in the catalog (no free-form settings). */
    public void set(String key, String value) {
        if (!catalog.containsKey(key))
            throw new IllegalArgumentException("Unknown setting: " + key);
        store.upsert(CurrentUser.organizationId(), CurrentUser.userId(), key, value);
    }
}
