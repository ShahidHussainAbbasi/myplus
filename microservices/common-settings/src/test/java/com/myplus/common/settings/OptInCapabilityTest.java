package com.myplus.common.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EX-0a — opt-in capabilities: OFF until the owner switches them on, never preset by a shape.
 *
 * <p>Design: microservices/docs/slices/ex-0a-capability-opt-in.md. Each case names the regression it catches.
 */
class OptInCapabilityTest {

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
        SettingsService settings = new SettingsService(store, List.of(new CapabilityCatalog()),
                Providers.none(), Providers.none(), 60L);
        return new CapabilityService(settings, EntitlementSource.PERMISSIVE);
    }

    /** The opt-in modules, listed by hand on purpose: adding one is a decision this test must be told about. */
    private static final java.util.Set<Capability> OPT_IN =
            java.util.EnumSet.of(Capability.EXPENSE_MANAGEMENT, Capability.MARKETPLACE_SELLING);

    @Test
    @DisplayName("Expense management is declared opt-in, and so is nothing else but the listed modules")
    void expense_management_is_opt_in() {
        assertThat(Capability.EXPENSE_MANAGEMENT.optIn()).isTrue();
        assertThat(Capability.EXPENSE_MANAGEMENT.defaultOn()).isFalse();
        assertThat(Capability.EXPENSE_MANAGEMENT.code()).isEqualTo("expenseManagement");
        // Every capability that existed before EX-0a keeps defaulting ON — a flipped flag on one of them would
        // take a screen away from every tenant on the deploy. MKT-0a: the list of opt-ins is explicit.
        for (Capability c : Capability.values()) {
            assertThat(c.optIn()).as("%s opt-in", c.code()).isEqualTo(OPT_IN.contains(c));
        }
    }

    @Test
    @DisplayName("MKT-0a: marketplace selling is opt-in, OFF for everyone, not in FREE, and survives a shape change")
    void marketplace_selling_is_opt_in_and_not_free() {
        Capability m = Capability.MARKETPLACE_SELLING;
        assertThat(m.code()).isEqualTo("marketplaceSelling");
        assertThat(m.optIn()).isTrue();
        assertThat(m.defaultOn()).isFalse();
        assertThat(Capability.defaultOnSet()).doesNotContain(m);
        assertThat(Capability.isOptInKey("org.cap.marketplaceSelling")).isTrue();
        // ruling R-MKT-6: a second sales channel is not what FREE exists for — the operator entitles it
        assertThat(Plan.FREE.includes(m)).isFalse();
        assertThat(Plan.PRO.includes(m)).isTrue();
        FakeStore store = new FakeStore();
        assertThat(svc(store).isEnabledFor(7L, m)).isFalse();
        store.upsert(7L, 1L, m.settingKey(), "true");
        assertThat(svc(store).isEnabledFor(7L, m)).isTrue();
        assertThat(svc(store).isEnabledFor(8L, m)).as("another tenant is untouched").isFalse();
        for (Shape s : Shape.values()) {
            assertThat(s.preset()).as("no shape presets an opt-in module: %s", s.code()).doesNotContain(m);
        }
    }

    @Test
    @DisplayName("an unconfigured tenant has it OFF — and an explicit ON is honoured")
    void off_by_default_on_when_chosen() {
        FakeStore store = new FakeStore();
        CapabilityService before = svc(store);
        assertThat(before.isEnabledFor(7L, Capability.EXPENSE_MANAGEMENT)).isFalse();

        // POSITIVE CONTROL: a resolver that always answered false would pass the line above.
        store.upsert(7L, 1L, Capability.EXPENSE_MANAGEMENT.settingKey(), "true");
        assertThat(svc(store).isEnabledFor(7L, Capability.EXPENSE_MANAGEMENT)).isTrue();
        assertThat(svc(store).isEnabledFor(8L, Capability.EXPENSE_MANAGEMENT))
                .as("one tenant's choice is not another's").isFalse();
    }

    @Test
    @DisplayName("no shape presets an opt-in module — not even GENERAL")
    void no_shape_presets_an_opt_in() {
        for (Shape s : Shape.values()) {
            for (Capability c : Capability.values()) {
                if (c.optIn()) {
                    assertThat(s.includes(c)).as("%s must not preset %s", s.code(), c.code()).isFalse();
                    assertThat(s.mandates(c)).as("%s must not mandate %s", s.code(), c.code()).isFalse();
                }
            }
        }
        // And GENERAL still presets everything a tenant had.
        for (Capability c : Capability.values()) {
            if (c.defaultOn()) assertThat(Shape.GENERAL.includes(c)).as(c.code()).isTrue();
        }
    }

    @Test
    @DisplayName("choosing a shape does not turn an opt-in module on")
    void picking_a_shape_does_not_enable_it() {
        for (Shape s : Shape.values()) {
            FakeStore store = new FakeStore();
            store.upsert(7L, 1L, Shape.settingKey(), s.code());
            assertThat(svc(store).isEnabledFor(7L, Capability.EXPENSE_MANAGEMENT)).as(s.code()).isFalse();
        }
    }

    @Test
    @DisplayName("the catalog publishes the switch with default OFF")
    void catalog_default_off() {
        SettingEntry e = new CapabilityCatalog().entries().stream()
                .filter(x -> Capability.EXPENSE_MANAGEMENT.settingKey().equals(x.key()))
                .findFirst().orElseThrow(() -> new AssertionError("no switch — shipped unreachable"));
        assertThat(e.defaultValue()).isEqualTo("false");
        assertThat(e.label()).isEqualTo("Expense management");
    }

    @Test
    @DisplayName("FREE includes it (R-2) — otherwise legacy tenants could never switch it on")
    void free_plan_includes_it() {
        assertThat(Plan.FREE.includes(Capability.EXPENSE_MANAGEMENT)).isTrue();
    }

    @Test
    @DisplayName("isOptInKey recognises only opt-in switches")
    void opt_in_key() {
        assertThat(Capability.isOptInKey("org.cap.expenseManagement")).isTrue();
        assertThat(Capability.isOptInKey("org.cap.installments")).isFalse();
        assertThat(Capability.isOptInKey("org.shape")).isFalse();
        assertThat(Capability.isOptInKey(null)).isFalse();
    }

    @Test
    @DisplayName("an OFF opt-in does not travel in the token claim; an ON one does")
    void wire_form() {
        FakeStore store = new FakeStore();
        assertThat(svc(store).encodeFor(7L).split(",")).doesNotContain("expenseManagement");
        store.upsert(7L, 1L, Capability.EXPENSE_MANAGEMENT.settingKey(), "true");
        assertThat(svc(store).encodeFor(7L).split(",")).contains("expenseManagement");
    }
}
