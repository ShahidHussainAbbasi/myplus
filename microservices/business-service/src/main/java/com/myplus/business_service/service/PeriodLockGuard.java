package com.myplus.business_service.service;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PeriodLockView;

/**
 * Period close for business ops. Reads the org's lock date from finance-service (the single source of truth) and
 * rejects a change dated in the closed period. Best-effort: if finance is unreachable it does NOT block (availability
 * over strictness) — the GL's own guard is the backstop. Called by the mutating ops with the EFFECTIVE date of the
 * change (today for new transactions; the original document's date for an edit/void).
 *
 * Performance: this sits on the hot path of every sale/purchase/payment, but the lock changes only at month-end, so
 * the finance read is cached per-org for a short TTL rather than a round-trip on every op. The GL's own postJournal
 * guard is the hard backstop for the (rare) TTL-staleness window just after a close.
 */
@Service
public class PeriodLockGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodLockGuard.class);

    /** Per-org lock cache TTL. Lock changes are rare (month-end), so a short cache keeps the finance read off the
     *  hot path; a close takes up to this long to propagate to the business ops (the GL guard is the hard backstop). */
    @org.springframework.beans.factory.annotation.Value("${app.period-lock.cache-ttl-ms:15000}")
    private long ttlMs;   // retained: the property is documented and read at startup; see the cache above

    @Autowired(required = false)
    private FinanceClient financeClient;   // shared GL; null if finance is unwired in this deployment

    @Autowired
    private RequestUtil requestUtil;

    /**
     * PERF-D5 — the shared {@link com.myplus.common.web.TenantCache}, replacing a hand-rolled
     * {@code ConcurrentHashMap<Long, Cached>} that had no size bound: keyed by organisation, it grew with
     * every tenant this process ever served and never returned an entry.
     *
     * <p><b>{@code Optional<LocalDate>}, not {@code LocalDate}.</b> Null is a legitimate cached answer here
     * — it means "no lock", which is the common case — and it is also what a failed read falls back to, so
     * that a finance-service blip costs one call per TTL rather than one per write. Caffeine does not record
     * a null mapping, so caching the bare date would have quietly stopped caching the ordinary case and
     * turned every write into a remote call: slower after the change than before it, while looking tidier.
     */
    private com.myplus.common.web.TenantCache<java.util.Optional<LocalDate>> cache;

    /*
     * Built in @PostConstruct, NOT at field initialisation.
     *
     * `ttlMs` is @Value-injected, and field injection runs AFTER the field initialisers — so a cache built
     * inline would capture 0 and the documented `app.period-lock.cache-ttl-ms` would be inert: present in
     * the config, reviewed, and doing nothing. That is the same trap PERF-C1 recorded for the settings
     * cache, and it is invisible unless somebody thinks to change the value and check.
     */
    @jakarta.annotation.PostConstruct
    void initCache() {
        this.cache = com.myplus.common.web.TenantCache.ofSeconds(Math.max(1, ttlMs / 1000));
    }

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    /** The org's lock date, or null (open / unavailable). Served from a short-lived per-org cache. */
    public LocalDate lockedThrough() {
        if (financeClient == null) return null;
        Long org = orgId();
        // -1L for a caller with no org, exactly as before. TenantCache declines to load under a null key —
        // correct for a picker, wrong here: it would skip the period-lock check entirely for those callers
        // rather than sharing one slot with them.
        Long key = org != null ? org : -1L;   // pre-migration/no-org rows share one cache slot
        return cache.get(key, k -> java.util.Optional.ofNullable(fetch())).orElse(null);
    }

    private LocalDate fetch() {
        try {
            PeriodLockView v = financeClient.getPeriodLock();
            if (v == null || v.getLockedThrough() == null || v.getLockedThrough().isBlank()) return null;
            return LocalDate.parse(v.getLockedThrough());
        } catch (Exception e) {
            LOG.warn("period-lock read failed (allowing the op)", e);
            return null;
        }
    }

    /** Reject the change if {@code date} is on/before the org's lock. */
    public void assertOpen(LocalDate date) {
        LocalDate locked = lockedThrough();
        if (locked != null && date != null && !date.isAfter(locked))
            throw new PeriodClosedException("This period is closed (locked through " + locked + "). Reopen it to make changes.");
    }
}
