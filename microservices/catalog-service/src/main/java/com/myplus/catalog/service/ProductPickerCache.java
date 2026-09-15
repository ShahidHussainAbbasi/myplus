package com.myplus.catalog.service;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.catalog.dto.ProductPickerDTO;
import com.myplus.common.web.TenantPagedCache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * CACHE-1 — the product picker's pages, cached per tenant, user and page; evicted after every committed product write.
 *
 * <p>The picker is the list every sale screen loads (id, name, sellingPrice, requiresSerial — no stock, no money
 * owed), read far more often than a product changes, and a SALE never writes a product. Design and the safe /
 * never-cache table: {@code microservices/docs/slices/cache-1-tenant-cache-aside.md}.
 *
 * <p><b>Why a component and not {@code @Cacheable}.</b> The annotation is proxy-based and was rejected here before
 * ({@code SettingsService}): a call from inside the same bean bypasses it, so it can be present, reviewed and inert.
 * {@link ProductService#getPicker} calls {@link #page} directly — nothing sits between the caller and the cache.
 *
 * <p><b>TTL is a backstop.</b> Correctness comes from {@link #onProductsChanged}; the TTL only bounds how long a page
 * could live if a writer ever failed to publish, and it is what keeps a second replica (none today:
 * {@code desired_count = 1}) from serving stale pages for longer than that. {@code 0} switches the cache off without a
 * redeploy; a negative value falls back to the default. Constructor-injected, because a {@code @Value} FIELD would still
 * be 0 while the cache was being built (the {@code SettingsService} lesson).
 */
@Component
public class ProductPickerCache {

    /** The {@code cache} tag on {@code cache.gets} / {@code cache.size} — how a gate proves the cache fires. */
    public static final String CACHE_NAME = "catalog.picker";
    static final long DEFAULT_TTL_SECONDS = 300;

    private final TenantPagedCache<Page<ProductPickerDTO>> pages;

    public ProductPickerCache(@Value("${app.cache.catalog-picker.ttl-seconds:300}") long ttlSeconds,
                              ObjectProvider<MeterRegistry> registry) {
        this.pages = TenantPagedCache.of(
                Duration.ofSeconds(ttlSeconds < 0 ? DEFAULT_TTL_SECONDS : ttlSeconds), 5_000);
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) CaffeineCacheMetrics.monitor(r, pages.nativeCache(), CACHE_NAME);
    }

    /** The page for this tenant and caller — from the cache, or {@code loader} (the database) on a miss. */
    public Page<ProductPickerDTO> page(Long org, Long user, Pageable pageable, Supplier<Page<ProductPickerDTO>> loader) {
        return pages.get(org, user, keyOf(pageable), loader);
    }

    /** Page number, size AND sort: the same page sorted differently is a different answer. */
    static String keyOf(Pageable p) {
        return p.isPaged()
                ? p.getPageNumber() + ":" + p.getPageSize() + ":" + p.getSort()
                : "unpaged:" + p.getSort();
    }

    /**
     * Evict AFTER COMMIT — and only then. A rolled-back write changed nothing in MySQL, so it evicts nothing.
     *
     * <p>{@code fallbackExecution = true} is for the one writer with no surrounding transaction, CSV import
     * ({@code ProductImportSpec.persist → saveAll}, which commits by itself): the event is published after
     * {@code saveAll} has returned, i.e. after its commit, so running at once is the after-commit case. Same shape as
     * {@code AuditEmitter}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductsChanged(CatalogProductsChanged event) {
        for (Long org : event.orgs()) pages.invalidateTenant(org);
    }

    /** Pages resident — for tests. */
    long size() {
        return pages.size();
    }
}
