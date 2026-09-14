package com.myplus.finance.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.finance.repository.OrgDocumentSeqRepo;

import lombok.RequiredArgsConstructor;

/**
 * DOC-INT B — the next per-org receipt / payment-voucher number, allocated so two receipts cannot share one.
 *
 * <h3>What this replaces</h3>
 * {@code COUNT(*) + 1} over the ledger. Two receipts recorded together counted the same total and took the same
 * number, and nothing refused the second. Here the number comes from a counter row, and the row lock the UPDATE
 * takes makes the second receipt WAIT.
 *
 * <h3>⚠ A deliberate copy</h3>
 * This is business-service's {@code DocumentNumberService} algorithm, in finance's own database (database per
 * service — neither reads the other's counters). The ORDER of the three steps below is the part that matters and
 * the part that was paid for: business's first two orderings deadlocked and lock-wait-timed-out respectively, and
 * its javadoc records why. Extracting both to a shared library is the right end state and a follow-up: doing it
 * here would mean changing business's gated allocator inside a slice about finance.
 *
 * <h3>{@code MANDATORY} — gapless, and loud when misused</h3>
 * The bump joins the CALLER's transaction ({@code PaymentService.record}), so a receipt that fails after taking a
 * number rolls the counter back and the number goes to the next receipt. A caller that forgot its transaction is
 * refused immediately instead of quietly committing a bump on its own.
 *
 * <h3>Allocate LATE</h3>
 * The lock is held until the caller commits, and every other receipt for that tenant waits behind it. Call this
 * immediately before the insert — never before a remote call.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private final OrgDocumentSeqRepo repo;

    /**
     * This bean through its proxy, so {@link #ensureCounter} really runs in its OWN transaction. A plain
     * {@code this.ensureCounter(...)} is a self-invocation that bypasses the proxy and would create the row inside
     * the caller's transaction after all.
     */
    private final ObjectProvider<DocumentNumberService> selfProvider;

    /**
     * Take the next number for this organisation and document type.
     *
     * @return the allocated number — 1 for the tenant's first document of that type
     * @throws IllegalArgumentException when the organisation is missing: a number no tenant owns is a number two
     *         tenants can both hold
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(Long orgId, String docType) {
        if (orgId == null) {
            throw new IllegalArgumentException("A document number needs an organisation.");
        }

        // 1. EXISTENCE, WITH A PLAIN READ, BEFORE ANYTHING TAKES A LOCK. An UPDATE that matches no row still takes
        //    a gap lock, which would block the separate transaction trying to create the row — business measured
        //    that as a 50-second lock-wait timeout on every tenant's first document.
        if (repo.current(orgId, docType) == null) {
            selfProvider.getObject().ensureCounter(orgId, docType);
        }

        // 2. THE SERIALISATION POINT: an exclusive lock on an existing row, no gap, no upgrade to deadlock on.
        if (repo.bump(orgId, docType) == 0) {
            throw new IllegalStateException("Document counter vanished for org " + orgId + " / " + docType);
        }

        // 3. Read back what was just allocated, behind the same lock.
        Long allocated = repo.current(orgId, docType);
        if (allocated == null) {
            throw new IllegalStateException("Document counter vanished for org " + orgId + " / " + docType);
        }
        return allocated;
    }

    /**
     * Create the counter at zero if it is missing, in its OWN committed transaction. Idempotent by
     * {@code INSERT IGNORE}; allocates nothing, so committing separately cannot create a gap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCounter(Long orgId, String docType) {
        repo.createCounterAtZero(orgId, docType);
    }
}
