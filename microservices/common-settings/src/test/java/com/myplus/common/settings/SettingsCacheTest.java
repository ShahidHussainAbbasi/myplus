package com.myplus.common.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PERF-C1 — the tenant-settings cache.
 *
 * <p>Pure JUnit, no Spring context: the cache lives in the object rather than behind a proxy (see
 * {@code SettingsService.overridesByOrg} for why {@code @Cacheable} could not work here), so the object
 * is the honest unit to test. It also means these run on every {@code mvn test} rather than only where a
 * container is available.
 *
 * <p>The store counts its own calls. "Fewer queries" is the claim being made, so queries are what is
 * counted — timing a second read would measure the machine, and would pass just as happily on a cache
 * that leaked one tenant's configuration to another.
 */
class SettingsCacheTest {

    /** A {@link SettingsStore} that records how often it is asked anything. */
    private static final class CountingStore implements SettingsStore {
        final Map<Long, Map<String, String>> rows = new LinkedHashMap<>();
        final AtomicInteger findAllCalls = new AtomicInteger();
        final AtomicInteger findCalls = new AtomicInteger();

        @Override public Optional<String> find(Long organizationId, String key) {
            findCalls.incrementAndGet();
            return Optional.ofNullable(rows.getOrDefault(organizationId, Map.of()).get(key));
        }

        @Override public List<Stored> findAll(Long organizationId) {
            findAllCalls.incrementAndGet();
            List<Stored> out = new ArrayList<>();
            rows.getOrDefault(organizationId, Map.of())
                .forEach((k, v) -> out.add(new Stored(k, v)));
            return out;
        }

        @Override public void upsert(Long organizationId, Long userId, String key, String value) {
            rows.computeIfAbsent(organizationId, o -> new LinkedHashMap<>()).put(key, value);
        }
    }

    private static SettingsCatalogProvider catalog() {
        return () -> List.of(
                SettingEntry.text("shop.name", "Shop name", "", "Acme Default", "Store"),
                SettingEntry.bool("shop.promo", "Show promo", "", false, "Store"));
    }

    private static SettingsService svc(CountingStore store) {
        return new SettingsService(store, List.of(catalog()), 60L);
    }

    // ── the property that matters most ──────────────────────────────────────────────────────────

    @Test
    void twoOrgsGetTheirOwnValues() {
        /*
         * THE TENANCY GATE.
         *
         * A cache keyed on the setting name alone would serve org 1's shop name to org 2 — silent,
         * absent from the logs, and invisible to every other test in this codebase because they all run
         * as a single organisation. This is the one failure that would matter, so it is asserted first
         * and asserted directly.
         */
        CountingStore store = new CountingStore();
        store.upsert(1L, 9L, "shop.name", "Javed Medicine");
        store.upsert(2L, 9L, "shop.name", "Shahzad Mobiles");
        SettingsService s = svc(store);

        assertEquals("Javed Medicine", s.effectiveFor(1L, "shop.name"));
        assertEquals("Shahzad Mobiles", s.effectiveFor(2L, "shop.name"));
        // ...and again, now that both are cached — the order must not matter either.
        assertEquals("Shahzad Mobiles", s.effectiveFor(2L, "shop.name"));
        assertEquals("Javed Medicine", s.effectiveFor(1L, "shop.name"));
    }

    // ── the point of the change ─────────────────────────────────────────────────────────────────

    @Test
    void manyReadsForOneOrgCostOneQuery() {
        // The receipt letterhead reads eight settings in a row. That was eight SELECTs.
        CountingStore store = new CountingStore();
        store.upsert(1L, 9L, "shop.name", "Javed Medicine");
        SettingsService s = svc(store);

        for (int i = 0; i < 8; i++) s.effectiveFor(1L, "shop.name");

        assertEquals(1, store.findAllCalls.get(), "eight reads, one query");
        assertEquals(0, store.findCalls.get(), "and never the per-key lookup");
    }

    @Test
    void aTenantOnPureDefaultsIsCachedToo() {
        /*
         * The commonest tenant of all is one that has overridden nothing. If an empty result were not
         * cached, that tenant would be the only one to see no benefit at all — and would re-query on
         * every single read, which is the exact behaviour this change exists to remove.
         */
        CountingStore store = new CountingStore();
        SettingsService s = svc(store);

        assertEquals("Acme Default", s.effectiveFor(7L, "shop.name"));
        assertEquals("Acme Default", s.effectiveFor(7L, "shop.name"));
        assertEquals("Acme Default", s.effectiveFor(7L, "shop.name"));

        assertEquals(1, store.findAllCalls.get(), "an empty override set is still an answer worth keeping");
    }

    // ── invalidation ────────────────────────────────────────────────────────────────────────────

    @Test
    void aWriteIsVisibleToTheVeryNextRead() {
        /*
         * Exact invalidation, not eventual. An owner who saves a setting and watches the screen not
         * change has been told the product is broken, and no TTL short enough to hide that is long
         * enough to be worth having.
         */
        CountingStore store = new CountingStore();
        store.upsert(1L, 9L, "shop.name", "Old Name");
        SettingsService s = svc(store);

        assertEquals("Old Name", s.effectiveFor(1L, "shop.name"));   // now cached

        store.upsert(1L, 9L, "shop.name", "New Name");
        s.invalidate(1L);                                            // what set() does in its finally

        assertEquals("New Name", s.effectiveFor(1L, "shop.name"));
    }

    @Test
    void invalidatingOneOrgDoesNotDisturbAnother() {
        // Eviction is per tenant. Flushing everything on every save would turn one shop's configuration
        // change into a thundering re-query for every other shop on the instance.
        CountingStore store = new CountingStore();
        store.upsert(1L, 9L, "shop.name", "One");
        store.upsert(2L, 9L, "shop.name", "Two");
        SettingsService s = svc(store);

        s.effectiveFor(1L, "shop.name");
        s.effectiveFor(2L, "shop.name");
        int after = store.findAllCalls.get();

        s.invalidate(1L);
        s.effectiveFor(2L, "shop.name");

        assertEquals(after, store.findAllCalls.get(), "org 2 was still cached");
    }

    // ── the paths that must NOT change ──────────────────────────────────────────────────────────

    @Test
    void anUnsetKeyStillFallsBackToTheCatalogDefault() {
        CountingStore store = new CountingStore();
        store.upsert(1L, 9L, "shop.promo", "true");        // a DIFFERENT key is set
        SettingsService s = svc(store);

        assertEquals("Acme Default", s.effectiveFor(1L, "shop.name"));
    }

    @Test
    void aCallerWithNoOrgStillGetsTheCatalogDefaultAndNeverQueries() {
        /*
         * The public storefront has no tenant in its security context. That branch never touched the
         * store before this change and must not start now — a null key in a per-org cache is how one
         * shopper's request ends up answering with another shop's policy.
         */
        CountingStore store = new CountingStore();
        SettingsService s = svc(store);

        assertEquals("Acme Default", s.effectiveFor(null, "shop.name"));
        assertEquals(0, store.findAllCalls.get(), "no tenant, no query");
        assertEquals(0, store.findCalls.get());
    }

    @Test
    void anUnknownKeyIsStillNull() {
        CountingStore store = new CountingStore();
        SettingsService s = svc(store);
        assertNull(s.effectiveFor(1L, "no.such.key"));
    }

    /*
     * NOT TESTED HERE: that catalogForOrg() (the Configuration screen) reads the same cached map as the
     * behaviour paths. It resolves its org from CurrentUser, a static security-context read, and standing
     * one up would make this a Spring test for no gain in what is actually proven — the shared-map change
     * is one line and the org==null path it preserves is already covered by SettingsCatalogProjectionTest.
     * The screen/behaviour agreement is worth a Cypress assertion, not a mock security context.
     */
}
