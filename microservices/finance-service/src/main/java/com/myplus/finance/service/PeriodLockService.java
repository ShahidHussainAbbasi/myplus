package com.myplus.finance.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.entity.PeriodLock;
import com.myplus.finance.repository.PeriodLockRepository;

import lombok.RequiredArgsConstructor;

/**
 * Period close — the single source of truth (finance owns the accounting close). One lock date per org; everything
 * dated on/before it is frozen. business-service reads {@link #lockedThrough()} to gate its ops; the GL enforces
 * {@link #assertOpen(LocalDate)} on posting.
 */
@Service
@RequiredArgsConstructor
public class PeriodLockService {

    private final PeriodLockRepository repo;

    /** The org's lock date, or null when nothing is locked. */
    @Transactional(readOnly = true)
    public LocalDate lockedThrough() {
        return repo.findByOrganizationId(CurrentUser.organizationId())
                .map(PeriodLock::getLockedThrough).orElse(null);
    }

    /** Close the books through {@code through} (or reopen by passing null). Records who/when. */
    @Transactional
    public LocalDate setLock(LocalDate through) {
        Long org = CurrentUser.organizationId();
        PeriodLock l = repo.findByOrganizationId(org)
                .orElseGet(() -> PeriodLock.builder().organizationId(org).build());
        l.setLockedThrough(through);
        l.setUpdatedBy(CurrentUser.userId());
        l.setUpdatedAt(LocalDateTime.now());
        repo.save(l);
        return through;
    }

    /** Reject a transaction dated in the closed period (date on/before the lock). */
    @Transactional(readOnly = true)
    public void assertOpen(LocalDate date) {
        LocalDate locked = lockedThrough();
        if (locked != null && date != null && !date.isAfter(locked))
            throw new PeriodClosedException("This period is closed (locked through " + locked + "). Reopen it to make changes.");
    }
}
