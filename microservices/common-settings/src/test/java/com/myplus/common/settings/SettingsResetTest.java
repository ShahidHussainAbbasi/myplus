package com.myplus.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;

/**
 * UI-CFG-1 — "Reset to default" REMOVES the override; saving the default value is not the same thing.
 *
 * <p>Why this matters: an explicit override pins a setting against the shop preset and the business type. A spec that
 * "restored" pos.product.showFormula by saving false pinned it, and the Pharmacy preset could no longer switch Formula
 * on (SET-CERT case B-020). Reset is the only honest way back.
 */
class SettingsResetTest {

    private static final Long ORG = 13L;

    private static final class MapStore implements SettingsStore {
        final Map<Long, Map<String, String>> rows = new LinkedHashMap<>();
        @Override public Optional<String> find(Long o, String k) { return Optional.ofNullable(rows.getOrDefault(o, Map.of()).get(k)); }
        @Override public List<Stored> findAll(Long o) {
            List<Stored> out = new ArrayList<>();
            rows.getOrDefault(o, Map.of()).forEach((k, v) -> out.add(new Stored(k, v)));
            return out;
        }
        @Override public void upsert(Long o, Long u, String k, String v) { rows.computeIfAbsent(o, x -> new LinkedHashMap<>()).put(k, v); }
        @Override public void remove(Long o, String k) { rows.getOrDefault(o, new LinkedHashMap<>()).remove(k); }
    }

    private final List<String> guardSaw = new ArrayList<>();
    private final List<String> listenerSaw = new ArrayList<>();
    private MapStore store;
    private SettingsService svc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "owner@test.com", List.of(), ORG), null, List.of()));
        store = new MapStore();
        SettingsCatalogProvider catalog = () -> List.of(
                SettingEntry.bool("pos.product.showFormula", "Formula", "", false, "Sale entry"),
                SettingEntry.bool("org.cap.gated", "Gated", "", true, "What this business does"));
        SettingWriteGuard guard = (org, key, value) -> {
            guardSaw.add(key + "=" + value);
            if ("org.cap.gated".equals(key) && "true".equals(value)) throw new IllegalArgumentException("not in your current plan");
        };
        SettingWriteListener listener = (org, key, before, after) -> listenerSaw.add(key + ":" + before + "->" + after);
        svc = new SettingsService(store, List.of(catalog), Providers.of(guard), Providers.of(listener), 60L);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void reset_REMOVES_the_override_rather_than_saving_the_default() {
        svc.set("pos.product.showFormula", "false");                      // pinned — equal to the default, still an override
        assertTrue(store.find(ORG, "pos.product.showFormula").isPresent());

        svc.reset("pos.product.showFormula");

        assertFalse(store.find(ORG, "pos.product.showFormula").isPresent(), "the override row is gone");
        assertNull(svc.overrideFor(ORG, "pos.product.showFormula").orElse(null), "and the cache agrees at once");
        assertEquals("false", svc.effectiveFor(ORG, "pos.product.showFormula"), "the catalog default decides again");
    }

    @Test
    void reset_tells_listeners_the_value_went_back_to_the_default() {
        svc.set("pos.product.showFormula", "true");
        listenerSaw.clear();
        svc.reset("pos.product.showFormula");
        assertEquals(List.of("pos.product.showFormula:true->null"), listenerSaw);
    }

    @Test
    void reset_asks_the_guards_about_the_DEFAULT_so_it_cannot_grant_what_a_write_would_refuse() {
        store.upsert(ORG, 7L, "org.cap.gated", "false");                   // the tenant switched it off; default is ON
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> svc.reset("org.cap.gated"));
        assertTrue(refused.getMessage().contains("plan"));
        assertTrue(guardSaw.contains("org.cap.gated=true"), "the guard saw the value the reset would fall back to");
        assertTrue(store.find(ORG, "org.cap.gated").isPresent(), "a refused reset leaves the override in place");
    }

    @Test
    void a_NULL_valued_override_is_still_an_override_and_reset_removes_it() {
        // org.cap.orderTypes=NULL on org 13: a present row the capability resolver reads as OFF. Checking the VALUE
        // made reset a silent no-op that still answered success.
        store.rows.computeIfAbsent(ORG, x -> new LinkedHashMap<>()).put("pos.product.showFormula", null);
        svc.reset("pos.product.showFormula");
        assertFalse(store.rows.get(ORG).containsKey("pos.product.showFormula"), "the NULL row is gone");
        assertEquals(List.of("pos.product.showFormula:null->null"), listenerSaw);
    }

    @Test
    void reset_with_nothing_to_reset_is_a_no_op() {
        svc.reset("pos.product.showFormula");
        assertTrue(guardSaw.isEmpty());
        assertTrue(listenerSaw.isEmpty());
    }

    @Test
    void a_save_with_NO_value_is_refused_and_points_to_reset() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> svc.set("pos.product.showFormula", null));
        assertTrue(refused.getMessage().contains("Reset to default"), refused.getMessage());
        assertFalse(store.find(ORG, "pos.product.showFormula").isPresent(), "no NULL row is written");
        assertTrue(guardSaw.isEmpty(), "refused before any guard or write");
    }

    @Test
    void a_BLANK_value_is_still_a_value__judged_by_its_type_not_as_missing() {
        // "" is not null: it reaches the TYPE check (a switch must be on or off — SettingsTypeValidationTest), not
        // the "no value, use Reset to default" refusal. (A TEXT setting takes "" — see that test.)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.set("pos.product.showFormula", ""));
        assertFalse(e.getMessage().contains("Reset to default"), e.getMessage());
        assertTrue(e.getMessage().contains("on or off"), e.getMessage());
    }

    @Test
    void an_unknown_key_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> svc.reset("no.such.key"));
    }

    @Test
    void a_store_that_has_not_adopted_reset_says_so() {
        SettingsStore legacy = new SettingsStore() {
            @Override public Optional<String> find(Long o, String k) { return Optional.of("true"); }
            @Override public List<Stored> findAll(Long o) { return List.of(new Stored("pos.product.showFormula", "true")); }
            @Override public void upsert(Long o, Long u, String k, String v) { }
        };
        SettingsService s = new SettingsService(legacy, List.of(() -> List.of(
                SettingEntry.bool("pos.product.showFormula", "Formula", "", false, "Sale entry"))), Providers.none(), Providers.none(), 60L);
        assertThrows(UnsupportedOperationException.class, () -> s.reset("pos.product.showFormula"));
    }
}
