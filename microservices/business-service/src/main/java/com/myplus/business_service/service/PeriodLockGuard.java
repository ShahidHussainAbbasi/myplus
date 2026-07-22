package com.myplus.business_service.service;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

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
    private long ttlMs;

    @Autowired(required = false)
    private FinanceClient financeClient;   // shared GL; null if finance is unwired in this deployment

    @Autowired
    private RequestUtil requestUtil;

    /** Cached lock per org: value carries the resolved date (nullable) + when it expires. */
    private record Cached(LocalDate lockedThrough, long expiresAt) {}
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    /** The org's lock date, or null (open / unavailable). Served from a short-lived per-org cache. */
    public LocalDate lockedThrough() {
        if (financeClient == null) return null;
        Long org = orgId();
        Long key = org != null ? org : -1L;   // pre-migration/no-org rows share one cache slot
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && c.expiresAt() > now) return c.lockedThrough();
        LocalDate resolved = fetch();
        cache.put(key, new Cached(resolved, now + ttlMs));
        return resolved;
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
