package com.myplus.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E1 — the entitlement CEILING, as pure JUnit so it runs on every {@code mvn test}.
 *
 * <p>Design: {@code microservices/docs/slices/e1-entitlement-ceiling.md}. The Cypress gate proves the ceiling
 * end to end through the real endpoints; these prove the ALGEBRA, which is the part a wiring change could
 * break silently. Each property below is one that would be invisible if it regressed.
 */
class EntitlementCeilingTest {

    private static final long ORG = 7L;

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

    /** A ceiling that has explicitly WITHDRAWN exactly the named capabilities. */
    private static EntitlementSource revoking(Capability... withdrawn) {
        Set<Capability> set = withdrawn.length == 0 ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(List.of(withdrawn));
        return new EntitlementSource() {
            @Override public boolean grantable(Long org, Capability c) { return c == null || !set.contains(c); }
            @Override public boolean revoked(Long org, Capability c) { return c != null && set.contains(c); }
        };
    }

    private static CapabilityService svc(FakeStore store, EntitlementSource ceiling) {
        SettingsService settings = new SettingsService(store, List.of(new CapabilityCatalog()), Guards.none(), 60L);
        return new CapabilityService(settings, ceiling);
    }

    // ── the algebra ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the ceiling SUBTRACTS — a capability the owner switched on is off once it is WITHDRAWN")
    void ceiling_removes_what_the_owner_enabled() {
        FakeStore store = new FakeStore();
        // The owner has explicitly said yes. Before E1 this was the final word.
        store.upsert(ORG, 1L, Capability.INSTALLMENTS.settingKey(), "true");

        CapabilityService svc = svc(store, revoking(Capability.INSTALLMENTS));

        assertThat(svc.isEnabledFor(ORG, Capability.INSTALLMENTS))
                .as("an explicit tenant override must not survive an operator withdrawing the entitlement")
                .isFalse();
    }

    @Test
    @DisplayName("⭐ the ceiling NEVER GRANTS — an entitled capability the owner switched off stays off")
    void ceiling_never_grants() {
        /*
         * The property the whole design rests on. A ceiling that could also ADD would make the effective set
         * after a deploy unpredictable; one that can only remove makes it a provable subset of the set before.
         */
        FakeStore store = new FakeStore();
        store.upsert(ORG, 1L, Capability.INSTALLMENTS.settingKey(), "false");

        CapabilityService svc = svc(store, revoking());

        assertThat(svc.isEnabledFor(ORG, Capability.INSTALLMENTS))
                .as("entitlement is a bound, not a grant — the owner's 'no' still wins")
                .isFalse();
    }

    @Test
    @DisplayName("not revoked AND enabled — both true is the only combination that is on")
    void both_terms_are_required() {
        FakeStore store = new FakeStore();
        store.upsert(ORG, 1L, Capability.INSTALLMENTS.settingKey(), "true");

        CapabilityService svc = svc(store, revoking());

        assertThat(svc.isEnabledFor(ORG, Capability.INSTALLMENTS)).isTrue();
    }

    @Test
    @DisplayName("⭐ SILENCE IS NOT A DECISION — a plan that omits a capability never turns one off")
    void the_plan_may_not_subtract_on_a_read() {
        /*
         * THE REGRESSION THAT SHIPPED, pinned at the level it actually lives.
         *
         * capability-shapes.cy.js reported every capability OFF for owner.mobile@. Cause: a legacy tenant
         * carries plan=FREE from @Builder.Default — a value nothing had ever read for capability — and the
         * first design consulted the PLAN on the read path. So the deploy that introduced the ceiling measured
         * every existing tenant against a plan nobody had sold them.
         *
         * The fix is not a wider default; it is that the read path asks a different QUESTION. `revoked` fires
         * only on positive evidence that somebody decided. A source that would refuse to GRANT a capability
         * must still not take it away from a tenant already using it.
         */
        EntitlementSource planBoundButNothingWithdrawn = new EntitlementSource() {
            @Override public boolean grantable(Long org, Capability c) { return false; }   // nothing may be enabled
            @Override public boolean revoked(Long org, Capability c) { return false; }     // but nothing was withdrawn
        };
        CapabilityService svc = svc(new FakeStore(), planBoundButNothingWithdrawn);

        for (Capability c : Capability.values()) {
            assertThat(svc.isEnabledFor(ORG, c))
                    .as("%s — a tenant keeps what it had; only an explicit withdrawal may subtract", c.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the PERMISSIVE default reproduces pre-E1 behaviour exactly")
    void permissive_default_changes_nothing() {
        /*
         * Every service except auth resolves against EntitlementSource.PERMISSIVE, because it reads the
         * ceiling's RESULT from the JWT claim and is not the authority on the question. That path must behave
         * precisely as it did before E1: a tenant with no configuration has everything on (GENERAL preset).
         */
        CapabilityService svc = svc(new FakeStore(), EntitlementSource.PERMISSIVE);

        for (Capability c : Capability.values()) {
            assertThat(svc.isEnabledFor(ORG, c))
                    .as("%s must still default ON with no ceiling in play", c.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the render side and the refusal side agree about a withdrawn capability")
    void render_and_refusal_agree() {
        /*
         * Two code paths answering the same question two ways is how a screen ends up hidden while its
         * endpoint still answers. The ceiling is applied inside the ONE resolver both halves go through, so
         * this asserts they do not disagree — the difference between them is only what they do when they
         * cannot tell. (There is no CurrentUser in a plain unit test, so assertEnabled is also refusing for
         * its fail-closed reason here; the point is that neither half permits.)
         */
        FakeStore store = new FakeStore();
        store.upsert(ORG, 1L, Capability.INSTALLMENTS.settingKey(), "true");
        CapabilityService svc = svc(store, revoking(Capability.INSTALLMENTS));

        assertThat(svc.isEnabledFor(ORG, Capability.INSTALLMENTS)).isFalse();
        assertThatThrownBy(() -> svc.assertEnabled(Capability.INSTALLMENTS))
                .isInstanceOf(com.myplus.common.web.exception.ValidationException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
                        .as("a refusal must not describe the configuration namespace")
                        .doesNotContain("org.cap"));
    }

    // ── the write guard chain ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a guard refuses the write BEFORE the store is touched")
    void guard_refuses_before_the_upsert() {
        FakeStore store = new FakeStore();
        SettingWriteGuard refuseAll = (org, key, value) -> {
            if (key.startsWith("org.cap.") && "true".equals(value))
                throw new IllegalArgumentException("\"Sell on installments\" is not included in your current plan.");
        };
        SettingsService settings =
                new SettingsService(store, List.of(new CapabilityCatalog()), Guards.of(refuseAll), 60L);

        // No CurrentUser in a plain unit test, so the org reaching the guard is null — which this guard
        // ignores, exactly as EntitlementWriteGuard's key/value check does before it consults the ceiling.
        assertThatThrownBy(() -> settings.set(Capability.INSTALLMENTS.settingKey(), "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not included in your current plan");

        assertThat(store.rows)
                .as("a refused write must not have reached the store at all")
                .isEmpty();

        // ⭐ Turning it OFF stays possible even while unentitled — C6's rule. Without this a withdrawn
        // capability would leave the tenant with a switch it can neither use nor clear.
        assertThatCode(() -> settings.set(Capability.INSTALLMENTS.settingKey(), "false"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the catalog reports a refused setting as locked, with the guard's own sentence")
    void catalog_reports_locked_rows() {
        /*
         * The lock the owner sees is DERIVED by asking the guard chain, never by a second rule that mirrors
         * it — so a control that is painted as available can never be one the server would refuse.
         */
        SettingWriteGuard refuseInstallments = (org, key, value) -> {
            if (Capability.INSTALLMENTS.settingKey().equals(key) && "true".equals(value))
                throw new IllegalArgumentException("\"Sell on installments\" is not included in your current plan.");
        };
        SettingsService settings = new SettingsService(
                new FakeStore(), List.of(new CapabilityCatalog()), Guards.of(refuseInstallments), 60L);

        List<Map<String, Object>> catalog = settings.catalogForOrg();
        Map<String, Object> installments = catalog.stream()
                .filter(m -> Capability.INSTALLMENTS.settingKey().equals(m.get("key")))
                .findFirst().orElseThrow();
        Map<String, Object> batch = catalog.stream()
                .filter(m -> Capability.BATCH_TRACKING.settingKey().equals(m.get("key")))
                .findFirst().orElseThrow();

        assertThat(installments.get("locked")).isEqualTo(true);
        assertThat(String.valueOf(installments.get("lockedReason"))).contains("not included in your current plan");
        assertThat(batch.get("locked"))
                .as("a setting no guard refuses must not be painted as locked")
                .isEqualTo(false);
    }

    // ── the plan map ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unreadable plan falls back to the NARROWEST tier, unlike an unreadable shape")
    void unknown_plan_falls_back_narrow() {
        /*
         * Shape.byCode falls back permissively because an unreadable shape must not stop a shop trading.
         * Plan.byCode must not: guessing generously about a LICENCE gives the product away to anything that
         * can write a typo into a column. The two directions are deliberate and opposite, so both are pinned.
         */
        assertThat(Plan.byCode("PLATINUM_ULTRA")).isEqualTo(Plan.FREE);
        assertThat(Plan.byCode(null)).isEqualTo(Plan.FREE);
        assertThat(Plan.byCode("  pro ")).isEqualTo(Plan.PRO);
        assertThat(Shape.byCode("nonsense")).isEqualTo(Shape.GENERAL);
    }

    @Test
    @DisplayName("every capability is sellable — no capability is absent from every plan")
    void every_capability_is_in_some_plan() {
        // A capability in no plan can never be bought, only granted by hand. That is a pricing mistake that
        // would otherwise surface as a customer who cannot switch on a feature the marketing site advertises.
        for (Capability c : Capability.values()) {
            boolean anywhere = false;
            for (Plan p : Plan.values()) anywhere |= p.includes(c);
            assertThat(anywhere).as("%s is in no plan at all", c.code()).isTrue();
        }
    }
}
