package com.myplus.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C1 — capabilities: what a tenant may do, and the server-side refusal that makes it real.
 *
 * <p>Pure JUnit against a fake store, so it runs on every {@code mvn test}. The properties below are the ones
 * the migration depends on; each is something that would be silent if it broke.
 */
class CapabilityServiceTest {

    /** A settings store backed by a plain map, so a test can say exactly what a tenant has overridden. */
    private static final class FakeStore implements SettingsStore {
        final Map<Long, Map<String, String>> rows = new LinkedHashMap<>();

        @Override public Optional<String> find(Long org, String key) {
            return Optional.ofNullable(rows.getOrDefault(org, Map.of()).get(key));
        }
        @Override public List<Stored> findAll(Long org) {
            List<Stored> out = new ArrayList<>();
            rows.getOrDefault(org, Map.of()).forEach((k, v) -> out.add(new Stored(k, v)));
            return out;
        }
        @Override public void upsert(Long org, Long userId, String key, String value) {
            rows.computeIfAbsent(org, o -> new LinkedHashMap<>()).put(key, value);
        }
    }

    private static CapabilityService svc(FakeStore store) {
        SettingsService settings = new SettingsService(store, List.of(new CapabilityCatalog()), 60L);
        return new CapabilityService(settings);
    }

    // ── the migration promise ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE MIGRATION — every capability is ON for a tenant that has configured nothing")
    void everything_defaults_on() {
        /*
         * This is the whole reason the rollout is safe. On the deploy that introduces capabilities, no tenant
         * has an org.cap.* row, so every one of them must resolve true and every screen and endpoint must
         * behave exactly as it did the day before. The slice changes where an answer comes FROM, never what
         * the answer is.
         *
         * If this ever fails, some tenant loses a screen it was using — which is a support call, not a tidy
         * migration.
         */
        CapabilityService svc = svc(new FakeStore());
        for (Capability c : Capability.values()) {
            assertThat(svc.isEnabledFor(7L, c))
                    .as("%s must default ON for an unconfigured tenant", c.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("POSITIVE CONTROL — an explicit OFF is honoured")
    void an_explicit_off_is_honoured() {
        // Without this, a service that always answered true would satisfy the case above perfectly.
        FakeStore store = new FakeStore();
        store.upsert(7L, 1L, Capability.SERIAL_TRACKING.settingKey(), "false");

        CapabilityService svc = svc(store);
        assertThat(svc.isEnabledFor(7L, Capability.SERIAL_TRACKING)).isFalse();
        assertThat(svc.isEnabledFor(7L, Capability.BATCH_TRACKING))
                .as("switching one off leaves the others alone").isTrue();
    }

    // ── tenancy ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("one tenant's switch does not reach another")
    void capabilities_are_per_tenant() {
        FakeStore store = new FakeStore();
        store.upsert(1L, 1L, Capability.FIELD_SALES.settingKey(), "false");

        CapabilityService svc = svc(store);
        assertThat(svc.isEnabledFor(1L, Capability.FIELD_SALES)).as("org 1 turned it off").isFalse();
        assertThat(svc.isEnabledFor(2L, Capability.FIELD_SALES)).as("org 2 never did").isTrue();
    }

    // ── the security half ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — assertEnabled REFUSES when there is no tenant to prove it for")
    void assert_fails_closed_without_a_tenant() {
        /*
         * isEnabled fails OPEN so a settings hiccup cannot take a screen away. assertEnabled must do the
         * opposite: it guards writes that touch stock, ledger or tax, and for those "we could not tell" has
         * to mean no. There is no CurrentUser in a plain unit test, so this is exactly the unprovable case.
         */
        CapabilityService svc = svc(new FakeStore());
        assertThatThrownBy(() -> svc.assertEnabled(Capability.SERIAL_TRACKING))
                .isInstanceOf(com.myplus.common.web.exception.ValidationException.class);
    }

    @Test
    @DisplayName("the refusal never names the tenant's configuration")
    void the_refusal_does_not_leak_configuration() {
        /*
         * A message that says which capabilities an org has — or has not — tells anyone probing endpoints
         * about a tenant they may not belong to. Same rule the anti-IDOR reads follow, where "not yours" and
         * "not there" are deliberately indistinguishable. So the wording refuses the ACTION and stops.
         */
        CapabilityService svc = svc(new FakeStore());
        assertThatThrownBy(() -> svc.assertEnabled(Capability.RX_REQUIRED))
                .hasMessageNotContaining("rxRequired")
                .hasMessageNotContaining("org.cap");
    }

    @Test
    @DisplayName("a null capability is not a refusal — nothing was asked for")
    void null_capability_is_permitted() {
        // Guards are added incrementally; a call site that has not named one yet must not start throwing.
        CapabilityService svc = svc(new FakeStore());
        assertThatCode(() -> svc.assertEnabled(null)).doesNotThrowAnyException();
        assertThat(svc.isEnabledFor(1L, null)).isTrue();
    }

    // ── the wire contract ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("enabledMap carries every capability, keyed by the code the markup uses")
    void enabled_map_is_keyed_by_code() {
        /*
         * The dashboard decides visibility for ~31 sections at load. It reads this map by the short code that
         * [data-capability] carries in the HTML, so a key that did not match the attribute would hide
         * everything — or nothing — with no error either way.
         */
        CapabilityService svc = svc(new FakeStore());
        Map<String, Boolean> map = svc.enabledMap();

        assertThat(map).hasSize(Capability.values().length);
        assertThat(map).containsKey("serialTracking").containsKey("batchTracking");
        assertThat(map.keySet()).allMatch(k -> Capability.byCode(k) != null,
                "every key resolves back to a Capability");
    }

    @Test
    @DisplayName("the settings namespace is reserved and built in one place")
    void setting_keys_share_one_namespace() {
        // A hand-written key elsewhere would sit outside the catalog, so it would have no default, no label
        // and no switch on the Configuration screen — present in the database and invisible to its owner.
        for (Capability c : Capability.values()) {
            assertThat(c.settingKey()).startsWith("org.cap.").endsWith(c.code());
        }
    }

    @Test
    @DisplayName("the catalog publishes every capability, so each one has a switch an owner can reach")
    void catalog_publishes_every_capability() {
        // A capability with no catalog entry is a capability no owner can turn off — the "shipped
        // unreachable" failure this codebase has hit before.
        List<SettingEntry> entries = new CapabilityCatalog().entries();
        assertThat(entries).hasSize(Capability.values().length);
        for (SettingEntry e : entries) {
            assertThat(e.defaultValue()).as("%s must default ON", e.key()).isEqualTo("true");
            assertThat(e.label()).isNotBlank();
            assertThat(e.help()).as("%s needs owner-facing help", e.key()).isNotBlank();
        }
    }
}
