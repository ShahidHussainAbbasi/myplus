package com.myplus.catalog.service;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.dto.TaxCodeDTO;
import com.myplus.common.web.TenantPagedCache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * CACHE-2 — the catalog's reference lists, cached per tenant and user; each evicted after its own data's committed write.
 *
 * <p>Three lists the Product form loads every time it opens (categories, tax codes, manufacturers), read far more often
 * than they change. Master data only — no stock, no money owed ({@code SAAS-BUILD-STANDARDS} §1d K5). Design and the
 * writer tables: {@code microservices/docs/slices/cache-1-tenant-cache-aside.md} §8.
 *
 * <table>
 *   <tr><th>Cache</th><th>Read</th><th>Evicted by</th></tr>
 *   <tr><td>{@value #CATEGORIES}</td><td>{@code CategoryService.getAll}</td><td>{@link CatalogCategoriesChanged}</td></tr>
 *   <tr><td>{@value #TAX_CODES}</td><td>{@code TaxCodeService.list}</td><td>{@link CatalogTaxCodesChanged}</td></tr>
 *   <tr><td>{@value #MANUFACTURERS}</td><td>{@code ProductService.manufacturers}</td><td>{@link CatalogProductsChanged}
 *       — there is no manufacturer table; the list is the distinct values on the tenant's products, so every product
 *       writer is its writer, and they already publish for the picker (CACHE-1)</td></tr>
 * </table>
 *
 * <p>Three caches rather than one keyed by kind, so a product save (every purchase stamps a price) empties only the
 * manufacturers list and leaves categories and tax codes cached.
 *
 * <p><b>One entry per tenant + user</b>, key page {@value #ALL}: these lists are read whole, never paged. The user is in
 * the key because the scope's NULL-org leg makes the answer depend on it (§4.1).
 *
 * <p><b>The cached lists are shared between requests.</b> Loaders return unmodifiable lists; the DTOs inside are
 * Lombok {@code @Data} and so mutable, and must not be changed by a caller — today the only callers are
 * {@code CategoryController.getAll}, {@code TaxCodeController.list} and {@code ProductController.manufacturers}, which
 * only serialise them.
 *
 * <p>TTL is a backstop, as in {@link ProductPickerCache}: {@code app.cache.catalog-refs.ttl-seconds}, default 300,
 * {@code 0} switches all three off without a redeploy, a negative value falls back to the default.
 */
@Component
public class CatalogRefsCache {

    /** The {@code cache} tags on {@code cache.gets} / {@code cache.size} — how a gate proves each cache fires. */
    public static final String CATEGORIES = "catalog.categories";
    public static final String TAX_CODES = "catalog.tax-codes";
    public static final String MANUFACTURERS = "catalog.manufacturers";
    /** CACHE-3 — one entry per PRODUCT, for the read screens' batch ref lookups. */
    public static final String REFS = "catalog.refs";

    static final long DEFAULT_TTL_SECONDS = 300;
    static final String ALL = "all";
    private static final long MAX_ENTRIES = 2_000;   // tenant × user pairs per list; each entry is one small list

    private final TenantPagedCache<List<CategoryDTO>> categories;
    private final TenantPagedCache<List<TaxCodeDTO>> taxCodes;
    private final TenantPagedCache<List<String>> manufacturers;
    /**
     * CACHE-3 — product refs, keyed one per product rather than per id-list: a grid asking for 730 ids and one asking
     * for 729 of them share every row, where a key built from the id list would share nothing and fill the cache with
     * near-duplicates. 20,000 entries covers several tenants' whole catalogues (the largest here holds ~6,000).
     */
    private final TenantPagedCache<com.myplus.commerce.contracts.dto.ProductRef> refs;

    public CatalogRefsCache(@Value("${app.cache.catalog-refs.ttl-seconds:300}") long ttlSeconds,
                            ObjectProvider<MeterRegistry> registry) {
        Duration ttl = Duration.ofSeconds(ttlSeconds < 0 ? DEFAULT_TTL_SECONDS : ttlSeconds);
        this.categories = TenantPagedCache.of(ttl, MAX_ENTRIES);
        this.taxCodes = TenantPagedCache.of(ttl, MAX_ENTRIES);
        this.manufacturers = TenantPagedCache.of(ttl, MAX_ENTRIES);
        this.refs = TenantPagedCache.of(ttl, 20_000);
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) {
            CaffeineCacheMetrics.monitor(r, categories.nativeCache(), CATEGORIES);
            CaffeineCacheMetrics.monitor(r, taxCodes.nativeCache(), TAX_CODES);
            CaffeineCacheMetrics.monitor(r, manufacturers.nativeCache(), MANUFACTURERS);
            CaffeineCacheMetrics.monitor(r, refs.nativeCache(), REFS);
        }
    }

    /**
     * CACHE-3 — a per-product view for ONE read: ask it id by id, fill it id by id, and load every miss in a single
     * query. The generation is pinned when this is taken, so rows loaded before a write commits cannot be stored as
     * current after its eviction (see {@code TenantPagedCache.snapshotFor}).
     *
     * <p>⚠ Only for the READ SCREENS. The sell saga passes {@code fresh} and never reaches this — what a customer is
     * charged is read live, every time.
     */
    public TenantPagedCache<com.myplus.commerce.contracts.dto.ProductRef>.Snapshot refs(Long org, Long user) {
        return refs.snapshotFor(org, user);
    }

    /** The cache key for one product's ref. */
    public static String refKey(Long productId) {
        return "ref:" + productId;
    }

    /** This tenant's categories — from the cache, or {@code loader} (the database) on a miss. */
    public List<CategoryDTO> categories(Long org, Long user, Supplier<List<CategoryDTO>> loader) {
        return categories.get(org, user, ALL, loader);
    }

    /** This tenant's tax codes — from the cache, or {@code loader} (the database) on a miss. */
    public List<TaxCodeDTO> taxCodes(Long org, Long user, Supplier<List<TaxCodeDTO>> loader) {
        return taxCodes.get(org, user, ALL, loader);
    }

    /** This tenant's distinct manufacturer names — from the cache, or {@code loader} (the database) on a miss. */
    public List<String> manufacturers(Long org, Long user, Supplier<List<String>> loader) {
        return manufacturers.get(org, user, ALL, loader);
    }

    /*
     * Evict AFTER COMMIT — and only then. A rolled-back write changed nothing in MySQL, so it evicts nothing.
     * fallbackExecution = true is for the writers with no surrounding transaction (CSV import's category auto-create and
     * saveAll, which commit by themselves): they publish after the save returned, i.e. after its commit.
     */

    /*
     * ⚠ CACHE-3 — ALL THREE events evict the refs, because a ProductRef is not built from the product row alone
     * (RULE 0, read toRef): it carries the CATEGORY NAME (so a rename must reach it) and a taxRate resolved through
     * resolveRate from the tenant's TAX CODES (so a rate change must too), on top of the product's own fields. Wiring
     * refs to product writes alone would have left a renamed category and a re-rated tax code showing the old values
     * on every read screen until the TTL.
     */

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCategoriesChanged(CatalogCategoriesChanged event) {
        for (Long org : event.orgs()) {
            categories.invalidateTenant(org);
            refs.invalidateTenant(org);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaxCodesChanged(CatalogTaxCodesChanged event) {
        for (Long org : event.orgs()) {
            taxCodes.invalidateTenant(org);
            refs.invalidateTenant(org);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductsChanged(CatalogProductsChanged event) {
        for (Long org : event.orgs()) {
            manufacturers.invalidateTenant(org);
            refs.invalidateTenant(org);
        }
    }

    /** Entries resident per cache — for tests. */
    long categoriesSize() { return categories.size(); }
    long taxCodesSize() { return taxCodes.size(); }
    long manufacturersSize() { return manufacturers.size(); }
}
