package com.myplus.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantPagedCacheTest {

    private final TenantPagedCache<String> cache = TenantPagedCache.of(Duration.ofMinutes(5), 1_000);

    // ── tenancy: the property whose failure is a data leak, not a slow page ─────────────────────────

    @Test
    @DisplayName("two tenants asking for the same page get their OWN page")
    void tenants_never_share_a_page() {
        assertThat(cache.get(1L, 10L, "0:50", () -> "org-1")).isEqualTo("org-1");
        assertThat(cache.get(2L, 10L, "0:50", () -> "org-2")).isEqualTo("org-2");
        assertThat(cache.get(1L, 10L, "0:50", () -> "WRONG")).isEqualTo("org-1");
    }

    @Test
    @DisplayName("two users of one tenant do not share a page — the scope's user leg is in the key")
    void users_of_one_tenant_do_not_share() {
        assertThat(cache.get(1L, 10L, "0:50", () -> "user-10")).isEqualTo("user-10");
        assertThat(cache.get(1L, 11L, "0:50", () -> "user-11")).isEqualTo("user-11");
    }

    @Test
    @DisplayName("a second read of the same page is served from the cache")
    void a_repeat_read_is_a_hit() {
        AtomicInteger loads = new AtomicInteger();
        cache.get(1L, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        cache.get(1L, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        assertThat(loads.get()).isEqualTo(1);
        assertThat(cache.nativeCache().stats().hitCount()).isEqualTo(1);
    }

    // ── invalidation: exact, per tenant ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidateTenant drops every page and user of THAT tenant, and leaves the others")
    void invalidation_is_per_tenant() {
        cache.get(1L, 10L, "0:50", () -> "a");
        cache.get(1L, 11L, "1:50", () -> "b");
        cache.get(2L, 10L, "0:50", () -> "other");

        cache.invalidateTenant(1L);

        assertThat(cache.get(1L, 10L, "0:50", () -> "a2")).isEqualTo("a2");
        assertThat(cache.get(1L, 11L, "1:50", () -> "b2")).isEqualTo("b2");
        assertThat(cache.get(2L, 10L, "0:50", () -> "WRONG")).isEqualTo("other");
    }

    // ── CACHE-3: the snapshot, for a read that resolves many keys and loads its misses in ONE query ──

    @Test
    @DisplayName("a snapshot serves what it stored, and says nothing about what it did not")
    void snapshot_stores_and_serves() {
        var snapshot = cache.snapshotFor(1L, 10L);

        assertThat(snapshot.getIfPresent("ref:1")).isNull();
        snapshot.put("ref:1", "product-1");

        assertThat(snapshot.getIfPresent("ref:1")).isEqualTo("product-1");
        assertThat(snapshot.getIfPresent("ref:2")).isNull();
        // And a later reader sees it too — this is the same cache, not a per-request map.
        assertThat(cache.get(1L, 10L, "ref:1", () -> "WRONG")).isEqualTo("product-1");
    }

    @Test
    @DisplayName("⭐ rows loaded BEFORE an eviction are not served AFTER it — the race the generation closes")
    void a_put_under_a_superseded_generation_is_never_served() {
        // The sequence that makes a plain cache-aside wrong: the reader misses, goes to the database, and while it
        // is away a write commits and evicts. Its rows are now stale before it has even stored them.
        var readerInFlight = cache.snapshotFor(1L, 10L);
        assertThat(readerInFlight.getIfPresent("ref:1")).isNull();

        cache.invalidateTenant(1L);          // a write committed while the reader was loading

        readerInFlight.put("ref:1", "STALE");  // stored under the generation the read began with

        // A later read must go to the database rather than be handed the stale row.
        assertThat(cache.get(1L, 10L, "ref:1", () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    @DisplayName("a snapshot for a caller with no tenant never stores and never hits")
    void snapshot_without_a_tenant_caches_nothing() {
        var snapshot = cache.snapshotFor(null, 10L);

        snapshot.put("ref:1", "no-tenant");

        assertThat(snapshot.getIfPresent("ref:1")).isNull();
        assertThat(cache.get(null, 10L, "ref:1", () -> "loaded")).isEqualTo("loaded");
    }

    @Test
    @DisplayName("two tenants' snapshots never see each other's rows")
    void snapshots_are_tenant_scoped() {
        cache.snapshotFor(1L, 10L).put("ref:1", "org-1");
        cache.snapshotFor(2L, 10L).put("ref:1", "org-2");

        assertThat(cache.snapshotFor(1L, 10L).getIfPresent("ref:1")).isEqualTo("org-1");
        assertThat(cache.snapshotFor(2L, 10L).getIfPresent("ref:1")).isEqualTo("org-2");
    }

    @Test
    @DisplayName("THE RACE — a read that loaded old rows before a write committed cannot survive that write's eviction")
    void a_load_in_flight_across_an_invalidation_is_not_served_afterwards() throws Exception {
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch writeCommitted = new CountDownLatch(1);

        // The reader has read the OLD rows and is about to store them…
        CompletableFuture<String> reader = CompletableFuture.supplyAsync(() -> cache.get(1L, 10L, "0:50", () -> {
            loaderStarted.countDown();
            await(writeCommitted);
            return "OLD";
        }));
        assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // …when the write commits and evicts.
        cache.invalidateTenant(1L);
        writeCommitted.countDown();
        assertThat(reader.get(5, TimeUnit.SECONDS)).isEqualTo("OLD");   // that request saw the old state — fine

        // Every read after the eviction must see the new state, not the page the slow reader stored.
        assertThat(cache.get(1L, 10L, "0:50", () -> "NEW")).isEqualTo("NEW");
    }

    // ── what is never cached ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a null tenant is never cached — the loader runs every time")
    void null_org_is_not_cached() {
        AtomicInteger loads = new AtomicInteger();
        cache.get(null, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        cache.get(null, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("a failed read (null) is not remembered — the next read retries")
    void null_result_is_not_cached() {
        AtomicInteger loads = new AtomicInteger();
        cache.get(1L, 10L, "0:50", () -> { loads.incrementAndGet(); return null; });
        assertThat(cache.get(1L, 10L, "0:50", () -> "ok")).isEqualTo("ok");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL zero switches the cache off — the diagnostic setting")
    void zero_ttl_is_off() {
        TenantPagedCache<String> off = TenantPagedCache.of(Duration.ZERO, 1_000);
        AtomicInteger loads = new AtomicInteger();
        off.get(1L, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        off.get(1L, 10L, "0:50", () -> "v" + loads.incrementAndGet());
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the size bound is real, not merely declared")
    void the_cache_is_bounded() {
        TenantPagedCache<String> small = TenantPagedCache.of(Duration.ofMinutes(5), 10);
        for (long page = 0; page < 500; page++) {
            final long p = page;
            small.get(1L, 10L, p + ":50", () -> "page-" + p);
        }
        assertThat(small.size()).isLessThanOrEqualTo(10);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
