package com.myplus.common.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 106 — the wire shape of {@code /settings}, which the self-rendering Configuration screen consumes.
 *
 * <p>THE DEFECT THIS GUARDS: {@code catalogForOrg()} hand-builds its JSON, and it omitted {@code options}.
 * {@code settings-form.js} renders a dropdown from {@code (it.options || [])}, so EVERY SELECT setting
 * rendered as an <em>empty</em> dropdown — the policy existed, defaulted correctly and was enforced, but an
 * owner could not change it anywhere except the API. It survived because the only settings test asserted a
 * BOOL entry, so no SELECT ever went through the screen's render path.
 *
 * <p>The lesson is the projection, not the record: {@link SettingEntry} always had {@code options} and
 * {@code defaultValue}. A hand-built map silently drops whatever the author forgets, and nothing in the type
 * system objects — so the projection needs its own test.
 *
 * <p>Pure — no Spring, no DB. Runs on {@code mvn test}.
 */
class SettingsCatalogProjectionTest {

    private static final String SELECT_KEY = "test.policy";
    private static final String BOOL_KEY = "test.flag";

    /** No overrides — with no security context the org is null, so every entry falls back to its default. */
    private static SettingsService serviceWithCatalog() {
        SettingsStore emptyStore = new SettingsStore() {
            @Override public List<Stored> findAll(Long organizationId) { return List.of(); }
            @Override public Optional<String> find(Long organizationId, String key) { return Optional.empty(); }
            @Override public void upsert(Long organizationId, Long userId, String key, String value) { }
        };
        SettingsCatalogProvider provider = () -> List.of(
                SettingEntry.select(SELECT_KEY, "Policy", "How strict to be", "warn", "Policy",
                        List.of(new SettingEntry.Option("off", "Off"),
                                new SettingEntry.Option("warn", "Warn"),
                                new SettingEntry.Option("block", "Block"))),
                SettingEntry.bool(BOOL_KEY, "Flag", "On or off", true, "Policy"));
        return new SettingsService(emptyStore, List.of(provider));
    }

    private static Map<String, Object> entry(List<Map<String, Object>> catalog, String key) {
        return catalog.stream().filter(m -> key.equals(m.get("key"))).findFirst().orElse(null);
    }

    @Test @DisplayName("A SELECT entry carries its options, or the screen renders an empty dropdown")
    @SuppressWarnings("unchecked")
    void selectCarriesItsOptions() {
        Map<String, Object> e = entry(serviceWithCatalog().catalogForOrg(), SELECT_KEY);
        assertNotNull(e, "the SELECT entry is in the catalog");
        assertEquals("SELECT", e.get("type"));

        List<SettingEntry.Option> options = (List<SettingEntry.Option>) e.get("options");
        assertNotNull(options, "options must reach the client — settings-form.js renders (it.options || [])");
        assertEquals(3, options.size(), "all three choices are offered");
        assertTrue(options.stream().anyMatch(o -> "block".equals(o.value())),
                "the strictest policy is reachable from the UI, not only the API");
    }

    @Test @DisplayName("Every entry declares its default, distinct from the effective value")
    void everyEntryDeclaresItsDefault() {
        List<Map<String, Object>> catalog = serviceWithCatalog().catalogForOrg();

        // Without defaultValue a caller cannot tell "warn because that IS the default" from "warn because
        // somebody set it" — and any assertion about the default has to hope no other actor changed it.
        assertEquals("warn", entry(catalog, SELECT_KEY).get("defaultValue"));
        assertEquals("true", entry(catalog, BOOL_KEY).get("defaultValue"));

        // With no override stored, the effective value equals the declared default and isDefault is true.
        assertEquals("warn", entry(catalog, SELECT_KEY).get("value"));
        assertEquals(Boolean.TRUE, entry(catalog, SELECT_KEY).get("isDefault"));
    }

    @Test @DisplayName("A non-SELECT entry carries an empty option list, never null")
    @SuppressWarnings("unchecked")
    void nonSelectCarriesEmptyOptions() {
        // Emitted for every type on purpose: a client that decides by type is a second place for the
        // contract to drift, and `null` would force every consumer to guard.
        List<SettingEntry.Option> options =
                (List<SettingEntry.Option>) entry(serviceWithCatalog().catalogForOrg(), BOOL_KEY).get("options");
        assertNotNull(options, "options is present even for BOOL");
        assertTrue(options.isEmpty(), "and empty");
    }
}
