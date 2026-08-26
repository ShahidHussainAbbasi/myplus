package com.myplus.common.web;

import java.time.Duration;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * PERF-D5 — one per-tenant read-through cache, instead of the same map written in every service.
 *
 * <h3>What it replaces</h3>
 * Two services had independently grown the identical thing: a {@code ConcurrentHashMap} keyed by
 * organisation, holding a value alongside an expiry timestamp, checked by hand on every read.
 *
 * <pre>
 *   PeriodLockGuard              org -&gt; period lock,  15s   (business-service)
 *   CheckoutService.taxPolicy    org -&gt; tax policy,   15s   (marketplace-service)
 * </pre>
 *
 * Both cache an expensive cross-service read of tenant configuration; both were written twice; and
 * <b>neither had a size bound</b>. That is the part worth fixing rather than tidying: a map keyed by
 * organisation grows with every tenant the process has ever served and never gives a single entry back. On
 * a platform whose whole point is many tenants, that is a slow leak with no upper limit.
 *
 * <h3>Why Caffeine rather than a fourth hand-rolled map</h3>
 * A size bound needs an eviction policy, and the choice matters more in a multi-tenant service than it
 * looks. Plain <b>LRU</b> is polluted by scale asymmetry: one large tenant running a report touches many
 * entries, those become the most-recently-used, and the working set of every small tenant is evicted —
 * so the majority of tenants get the misses. Caffeine's default is <b>W-TinyLFU</b>, where a frequency
 * sketch decides what is even <em>admitted</em>, so a burst cannot displace an entry it will never match
 * again.
 *
 * <h3>TTL and size are not alternatives</h3>
 * They answer different questions and this uses both: the TTL bounds <em>staleness</em> (how wrong may
 * this be?), the maximum size bounds <em>memory</em> (what goes when it is full?). A cache with only a TTL
 * is unbounded; one with only a size bound serves yesterday's configuration forever.
 *
 * <h3>What this is NOT for</h3>
 * <b>Counters and rate windows.</b> {@code RateLimitGlobalFilter} and {@code CaptchaAttemptService} hold
 * superficially similar maps, and moving them here would be a security regression: for a cache a miss is
 * merely slow, but evicting a rate-limit window hands the caller a fresh quota, and evicting a failed-login
 * counter forgives the attempts. Those maps are state, not cache, and they are deliberately left where
 * they are.
 *
 * @param <V> the cached value; the key is always the organisation id
 */
public final class TenantCache<V> {

    private final Cache<Long, V> cache;

    private TenantCache(Duration ttl, long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .build();
    }

    /**
     * @param ttl         how stale an answer may be. Seconds, for tenant configuration a person can change
     *                    on a settings screen and expects to take effect.
     * @param maximumSize the ceiling on tenants held at once. Not a guess at how many tenants exist — a
     *                    guarantee that the map cannot grow without limit if there are more.
     */
    public static <V> TenantCache<V> of(Duration ttl, long maximumSize) {
        return new TenantCache<>(ttl, maximumSize);
    }

    /** A sensible default for tenant configuration: 1,000 tenants resident, refreshed on the given TTL. */
    public static <V> TenantCache<V> ofSeconds(long ttlSeconds) {
        return of(Duration.ofSeconds(Math.max(1, ttlSeconds)), 1_000L);
    }

    /**
     * The cached value for this tenant, loading it if absent or expired.
     *
     * <p>A null {@code org} is not cached and not loaded — an unauthenticated or cross-tenant caller has no
     * tenant configuration, and inventing a shared entry under a null key is how one tenant's policy ends
     * up answering for another. Callers get null and fall back to whatever their own default is.
     *
     * <p>If {@code loader} returns null the miss is NOT cached, so a failed remote read is retried on the
     * next request rather than remembered for the whole TTL. That matters for the two callers this was
     * built for: both fail soft, and caching the failure would extend a momentary outage into a
     * fifteen-second one.
     */
    public V get(Long org, Function<Long, V> loader) {
        if (org == null) return null;
        return cache.get(org, loader::apply);
    }

    /** Drop one tenant's entry — for a caller that knows its own write invalidated it. */
    public void invalidate(Long org) {
        if (org != null) cache.invalidate(org);
    }

    /** Drop everything. Intended for tests and for an explicit administrative reset. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Entries currently resident. Exposed so a test can prove the bound is real rather than declared. */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
