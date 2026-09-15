package com.myplus.common.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * CACHE-1 — lazy, tenant-scoped, PAGINATED cache-aside for safe read data. The sibling of {@link TenantCache}, whose
 * key is the organisation alone and so cannot hold "page 3 of this tenant's list".
 *
 * <h3>The rule this implements (the user, 2026-09-15)</h3>
 * Read: cache → on a miss the database → store with a TTL → return. Write: commit to MySQL first, then invalidate
 * the affected tenant, and only after the commit succeeded. MySQL is the source of truth; this is disposable.
 * The after-commit half lives with the caller (a {@code @TransactionalEventListener(AFTER_COMMIT)}), because only
 * the caller knows its transaction — see {@code slices/cache-1-tenant-cache-aside.md}.
 *
 * <h3>The key: tenant, user, page</h3>
 * The catalog's reads are scoped {@code organizationId = :org OR (organizationId IS NULL AND userId = :user)}, so two
 * users of one tenant can legitimately see different rows. A key of the tenant alone would let one user's legacy
 * rows answer for another; the user is in the key so that holds whatever the data contains.
 *
 * <h3>The race a plain cache-aside has, and the generation that closes it</h3>
 * A read that loaded the OLD rows just before a write committed can store them just AFTER the write's eviction ran,
 * and that stale page is then served until the TTL. For a picker row that is not cosmetic — a stale
 * {@code requiresSerial} keeps asking a till for serial numbers. So every tenant carries a generation: eviction
 * bumps it, the key carries the generation the read STARTED under, and a page loaded under an old generation is
 * stored where no later read looks. The generation map holds one {@code Long} per tenant ever written to — the one
 * deliberately unbounded structure here, because evicting a generation would let an old key become current again.
 *
 * <h3>What a caller must not do</h3>
 * Mutate a returned value. It is the cached instance, shared with every later read of that page.
 *
 * @param <V> the cached page
 */
public final class TenantPagedCache<V> {

    /** One cached page. {@code generation} is the tenant's generation when the read began. */
    public record Key(long org, long generation, Long user, String page) {}

    private final Cache<Key, V> cache;
    private final Map<Long, Long> generations = new ConcurrentHashMap<>();

    private TenantPagedCache(Duration ttl, long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)      // a BACKSTOP: correctness comes from invalidateTenant on write
                .maximumSize(maximumSize)   // W-TinyLFU: one big tenant's burst cannot evict every small tenant
                .recordStats()              // so a gate can prove the cache fires, not assume it from its presence
                .build();
    }

    /**
     * @param ttl         how long a page may live if an eviction were ever missed. {@code Duration.ZERO} switches the
     *                    cache off (every entry expires at once) — the diagnostic setting.
     * @param maximumSize the ceiling on pages held at once, across all tenants
     */
    public static <V> TenantPagedCache<V> of(Duration ttl, long maximumSize) {
        return new TenantPagedCache<>(ttl, maximumSize);
    }

    /**
     * The page for this tenant and caller, loading it on a miss.
     *
     * <p>A null {@code org} is not cached — the loader runs every time. An unauthenticated or system caller has no
     * tenant, and a shared entry under a null key is how one tenant's rows end up answering for another. A null
     * loader result is not cached either, so a failed read is retried rather than remembered.
     */
    public V get(Long org, Long user, String page, Supplier<V> loader) {
        if (org == null || page == null) return loader.get();
        long generation = generations.getOrDefault(org, 0L);
        return cache.get(new Key(org, generation, user, page), k -> loader.get());
    }

    /**
     * Every page of this tenant, for every user, is stale from now on.
     *
     * <p>Bumping the generation is what makes it correct (a read already in flight stores under the old one); removing
     * the old entries only returns their memory now instead of at the TTL.
     */
    public void invalidateTenant(Long org) {
        if (org == null) return;
        generations.merge(org, 1L, Long::sum);
        long o = org;
        cache.asMap().keySet().removeIf(k -> k.org() == o);
    }

    /** Drop everything. For tests and an explicit administrative reset. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Pages currently resident — so a test can prove the bound is real rather than declared. */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /** The underlying Caffeine cache, for binding its hit/miss statistics to a metrics registry. */
    public Cache<Key, V> nativeCache() {
        return cache;
    }
}
