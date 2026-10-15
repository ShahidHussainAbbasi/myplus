package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myplus.business_service.dto.SellDTO;

/**
 * INV-CACHE (owner, 2026-10-15: "fresh invoice data should be cached to avoid … re-fetching it from the database") — the
 * LINES of a printed invoice, cache-aside per (business, invoice). Standard: SAAS-BUILD-STANDARDS §1d / CACHE-1.
 *
 * <h3>What is cached, and what never is</h3>
 * Only the expensive, line-level part of a receipt: the sale lines with their catalog names and batches, and the store
 * credit applied. The invoice HEADER — paid, due, totals, balance, payment mode, the customer — is read live on every
 * print (getReceipt reads it anyway, to decide whether the caller may see the invoice), so no money figure on a printed
 * invoice is ever served from here. Settings (letterhead, layout, tax label) are read live too.
 *
 * <h3>Eviction — every writer, after commit</h3>
 * {@link InvoiceLinesEvictor} is an entity listener on {@code Sell}, {@code SellBatch} and {@code Payment}: every JPA
 * writer of those tables (4 + 1 + 2 classes on 15 Oct; no native SQL writes them) evicts the invoice it touched — at
 * once, and again AFTER the commit, so a print that read the old rows while the write was in flight cannot put them
 * back (the generation check below). A rolled-back write evicts too, which costs one rebuild and is never wrong.
 * Catalog renames are not seen here: a product's new name reaches a cached invoice within the TTL.
 *
 * <p>{@code app.cache.invoice-lines.ttl-seconds} (default 300; 0 = off, every print reads the database).
 */
@Component
public class InvoiceLinesCache {

    /** The cached part of one invoice. Treated as read-only by every caller. */
    public record Lines(List<SellDTO> sales, BigDecimal storeCreditApplied) {}

    private final Cache<String, Lines> cache;
    /** Bumped on every eviction of an invoice; a load that started before the bump does not store its (older) result. */
    private final Cache<Long, AtomicLong> generations;
    private final boolean enabled;

    public InvoiceLinesCache(@Value("${app.cache.invoice-lines.ttl-seconds:300}") long ttlSeconds) {
        this.enabled = ttlSeconds > 0;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .maximumSize(10_000).recordStats().build();
        this.generations = Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(100_000).build();
    }

    static String key(Long org, Long invoiceId) {
        return org + ":" + invoiceId;
    }

    /** Cache-aside: the cached lines, or {@code loader} (which reads the database) — stored only if nothing evicted meanwhile. */
    public Lines get(Long org, Long invoiceId, Supplier<Lines> loader) {
        if (!enabled || org == null || invoiceId == null) return loader.get();
        String k = key(org, invoiceId);
        Lines hit = cache.getIfPresent(k);
        if (hit != null) return hit;
        long before = generation(invoiceId).get();
        Lines fresh = loader.get();
        // Never cache an empty answer (a voided invoice keeps no lines) — it would hide the lines a later write adds.
        if (fresh != null && fresh.sales() != null && !fresh.sales().isEmpty() && generation(invoiceId).get() == before)
            cache.put(k, fresh);
        return fresh;
    }

    /** Drop one invoice now, and again after the surrounding transaction commits (or completes). */
    public void evict(Long org, Long invoiceId) {
        if (invoiceId == null) return;
        drop(org, invoiceId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { drop(org, invoiceId); }
            });
        }
    }

    /** By (business, invoice); a row with no business on it (pre-tenancy data) drops that invoice under every business. */
    private void drop(Long org, Long invoiceId) {
        generation(invoiceId).incrementAndGet();
        if (org != null) cache.invalidate(key(org, invoiceId));
        else cache.asMap().keySet().removeIf(k -> k.endsWith(":" + invoiceId));
    }

    private AtomicLong generation(Long invoiceId) {
        return generations.get(invoiceId, x -> new AtomicLong());
    }

    /** For the gate and the log: how many entries, hits and misses. */
    public String stats() {
        cache.cleanUp();
        return "size=" + cache.estimatedSize() + " " + cache.stats();
    }
}
