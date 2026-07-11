package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.IdempotencyRecord;
import com.myplus.business_service.repository.IdempotencyRepo;

import lombok.RequiredArgsConstructor;

/**
 * Audit #5: the shared money-op idempotency guard (generalizes the SF-3 sale pattern). A caller wraps its work as:
 * <pre>
 *   if (key present) {
 *     Optional&lt;String&gt; prior = find(org, op, key);   // a prior (committed) submit → replay its result
 *     if (prior.isPresent()) return replay(prior.get());
 *   }
 *   ... do the work, get resultRef ...
 *   record(org, op, key, resultRef);                       // ATOMIC with the work (same tx)
 * </pre>
 * The record is written in the CALLER'S transaction so it commits atomically with the work — never a claimed-but-empty
 * row. Dedup semantics:
 * <ul>
 *   <li><b>Sequential</b> double-submit (double-click / retry after the first finished): the pre-check {@link #find}
 *       runs in a fresh request/transaction whose snapshot sees the committed row → replay.</li>
 *   <li><b>Concurrent</b> race (two in-flight at once): both pass the pre-check and do the work, then both insert;
 *       the unique index lets one commit and the other fail at commit → that op's tx rolls back (no double-apply),
 *       and its retry then replays.</li>
 * </ul>
 * A blank key disables the guard (legacy callers — no behavior change). NOTE: deliberately NOT REQUIRES_NEW — a
 * separate-tx claim is invisible to the caller's (REPEATABLE READ) snapshot, so its result_ref could never be set.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRepo repo;

    /** The stored result_ref for a completed op, or empty if never recorded. Joins the caller's tx (fresh per request). */
    @Transactional(readOnly = true)
    public Optional<String> find(Long org, String op, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repo.findByOrganizationIdAndOperationAndIdemKey(org, op, key)
                .map(IdempotencyRecord::getResultRef);
    }

    /** Record a completed op in the caller's tx (atomic with the work). Unique (org, op, key) arbitrates a race. */
    public void record(Long org, String op, String key, String resultRef) {
        if (key == null || key.isBlank()) return;
        IdempotencyRecord r = new IdempotencyRecord();
        r.setOrganizationId(org);
        r.setOperation(op);
        r.setIdemKey(key);
        r.setResultRef(resultRef);
        r.setCreatedAt(LocalDateTime.now());
        repo.save(r);   // flushes at commit → a concurrent race violates the unique index and rolls that op back
    }
}
