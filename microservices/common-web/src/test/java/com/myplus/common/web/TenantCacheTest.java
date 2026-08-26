package com.myplus.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PERF-D5 — the shared per-tenant cache.
 *
 * <p>Pure logic, no Spring, no database: it runs on every {@code mvn test}. The properties below are the
 * ones the two callers depend on, and each is a thing that was either wrong or unbounded before.
 */
class TenantCacheTest {

    // ── the point of the change ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — the size bound is real, not merely declared")
    void the_cache_is_actually_bounded() {
        /*
         * The reason this class exists. Both maps it replaces were ConcurrentHashMaps keyed by organisation:
         * they grew with every tenant the process had ever served and never returned an entry. On a platform
         * whose whole point is many tenants that is a slow leak with no ceiling.
         *
         * A declared maximumSize proves nothing on its own — this loads well past it and asks how many
         * entries are actually resident.
         */
        TenantCache<String> cache = TenantCache.of(Duration.ofMinutes(10), 10);
        for (long org = 1; org <= 500; org++) {
            final long o = org;
            cache.get(o, k -> "tenant-" + o);
        }
        assertThat(cache.size())
                .as("500 tenants asked for, at most 10 held")
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("POSITIVE CONTROL — a hit does not call the loader again")
    void a_hit_does_not_reload() {
        // Without this, a cache that never stored anything would satisfy the bound above perfectly.
        AtomicInteger loads = new AtomicInteger();
        TenantCache<String> cache = TenantCache.ofSeconds(60);

        for (int i = 0; i < 5; i++) cache.get(7L, k -> "loaded-" + loads.incrementAndGet());

        assertThat(loads.get()).as("five reads, one load").isEqualTo(1);
        assertThat(cache.get(7L, k -> "should-not-run")).isEqualTo("loaded-1");
    }

    // ── tenancy ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tenants do not see each other's values")
    void entries_are_per_tenant() {
        TenantCache<String> cache = TenantCache.ofSeconds(60);
        cache.get(1L, k -> "one");
        cache.get(2L, k -> "two");

        assertThat(cache.get(1L, k -> "reloaded")).isEqualTo("one");
        assertThat(cache.get(2L, k -> "reloaded")).isEqualTo("two");
    }

    @Test
    @DisplayName("a null tenant is neither loaded nor cached")
    void null_org_is_refused() {
        /*
         * An unauthenticated or cross-tenant caller has no tenant configuration. Inventing a shared entry
         * under a null key is how one tenant's policy ends up answering for another — so the loader is not
         * even run, and the caller falls back to its own default.
         */
        AtomicInteger loads = new AtomicInteger();
        TenantCache<String> cache = TenantCache.ofSeconds(60);

        assertThat(cache.get(null, k -> { loads.incrementAndGet(); return "leaked"; })).isNull();
        assertThat(loads.get()).as("the loader never ran").isZero();
        assertThat(cache.size()).isZero();
    }

    // ── the behaviour PeriodLockGuard depends on ────────────────────────────────────────────────

    @Test
    @DisplayName("a null RESULT is not recorded, so a failed read is retried rather than remembered")
    void null_results_are_not_cached() {
        /*
         * This is the property that decided how PeriodLockGuard had to migrate. Caffeine does not record a
         * null mapping, so caching a bare nullable value would have stopped caching the commonest answer —
         * "no period lock" — and turned every write into a remote call: SLOWER after the change than before,
         * while looking tidier. PeriodLockGuard therefore caches Optional<LocalDate>.
         *
         * For a genuine failure this is the behaviour you want: retry next time, do not remember it.
         */
        AtomicInteger loads = new AtomicInteger();
        TenantCache<String> cache = TenantCache.ofSeconds(60);

        cache.get(3L, k -> { loads.incrementAndGet(); return null; });
        cache.get(3L, k -> { loads.incrementAndGet(); return null; });

        assertThat(loads.get()).as("each miss retried; the null was never stored").isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("…and Optional.empty() IS recorded, which is how a nullable value stays cached")
    void an_empty_optional_is_cached() {
        AtomicInteger loads = new AtomicInteger();
        TenantCache<java.util.Optional<String>> cache = TenantCache.ofSeconds(60);

        for (int i = 0; i < 4; i++) {
            cache.get(4L, k -> { loads.incrementAndGet(); return java.util.Optional.empty(); });
        }
        assertThat(loads.get()).as("wrapped, so 'no value' is still an answer worth keeping").isEqualTo(1);
    }

    // ── invalidation ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidating one tenant leaves the others alone")
    void invalidate_is_per_tenant() {
        AtomicInteger loads = new AtomicInteger();
        TenantCache<String> cache = TenantCache.ofSeconds(60);
        cache.get(1L, k -> "one-" + loads.incrementAndGet());
        cache.get(2L, k -> "two-" + loads.incrementAndGet());

        cache.invalidate(1L);

        assertThat(cache.get(2L, k -> "reloaded")).as("tenant 2 untouched").isEqualTo("two-2");
        assertThat(cache.get(1L, k -> "reloaded")).as("tenant 1 refetched").isEqualTo("reloaded");
    }

    @Test
    @DisplayName("expiry returns the value to the loader")
    void entries_expire() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        // The shortest TTL the factory permits, so the test measures expiry rather than the clock.
        TenantCache<String> cache = TenantCache.of(Duration.ofMillis(40), 100);

        cache.get(9L, k -> "v" + loads.incrementAndGet());
        Thread.sleep(120);
        cache.get(9L, k -> "v" + loads.incrementAndGet());

        assertThat(loads.get()).as("the second read came after the TTL, so it reloaded").isEqualTo(2);
    }
}
