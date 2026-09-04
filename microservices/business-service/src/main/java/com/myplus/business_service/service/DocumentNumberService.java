package com.myplus.business_service.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.repository.OrgDocumentSeqRepo;

import lombok.RequiredArgsConstructor;

/**
 * The next per-org document number, allocated so that two tills cannot take the same one.
 *
 * <h3>What this replaces, and why</h3>
 * {@code SELECT MAX(seq) + 1} is not an allocation, it is a guess that is usually right. Two concurrent
 * callers read the same maximum, both take it, and the UNIQUE constraint refuses the loser — whose operation
 * then failed with {@code "Transaction silently rolled back because it has been marked as rollback-only"}.
 *
 * <p>Here the number comes from a counter row, and the row lock the UPDATE takes is what makes the second
 * caller wait rather than collide. <b>The collision is prevented, not recovered from</b> — which matters
 * because the operations that need this have already touched inventory by the time they allocate, so replaying
 * them would put stock back twice. {@code SequenceRetry} recovers where replay is safe (the invoice and plan
 * writes, both pure database work in their own transaction); this prevents where it is not.
 *
 * <h3>⚠ {@code MANDATORY} is load-bearing — it is what makes the numbering gapless</h3>
 * The bump must be part of the CALLER's transaction. A return that fails after taking number 42 then rolls the
 * counter back with everything else, and 42 goes to the next caller instead of being burned. Credit notes are
 * tax documents; an unexplained gap in them is a question somebody answers at an audit.
 *
 * <p>{@code MANDATORY} refuses to run outside a transaction rather than quietly starting one of its own —
 * which would commit independently and reintroduce exactly the gaps this exists to avoid. A caller that has
 * forgotten its {@code @Transactional} finds out immediately instead of six months later.
 *
 * <h3>⚠ Allocate LATE</h3>
 * The row lock is held from here until the caller commits, so every other till selling for that tenant waits
 * behind it. Call this immediately before the insert that needs the number — <b>never before a remote call</b>,
 * or the lock is held across the network and one slow inventory round trip stalls the whole tenant.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    public static final String DEBIT_NOTE = "DEBIT_NOTE";
    public static final String QUOTE = "QUOTE";
    public static final String INVOICE = "INVOICE";
    public static final String PLAN = "PLAN";
    /**
     * OB-1 — opening balances get their OWN series (OB-000001), never the invoice one.
     *
     * An opening balance consuming an INV- number would leave a gap in the shop's invoice sequence at
     * exactly the point an auditor looks hardest: the migration. The series is per-org like the others.
     */
    public static final String OPENING = "OPENING";

    private final OrgDocumentSeqRepo repo;

    /**
     * This bean, through the proxy. {@link #ensureCounter} must run in its OWN transaction, and a plain
     * {@code this.ensureCounter(...)} would be a self-invocation — which never passes through the proxy, so
     * the annotation would be decorative and the row would be created inside the caller's transaction after
     * all. That is exactly the trap that made INST-3a's scanner and this class's first two attempts wrong.
     */
    private final org.springframework.beans.factory.ObjectProvider<DocumentNumberService> selfProvider;

    private DocumentNumberService self() {
        return selfProvider.getObject();
    }

    /**
     * Take the next number for this organisation and document type.
     *
     * @return the allocated number, 1 for the tenant's first document of that type
     * @throws IllegalArgumentException when the organisation is unknown — a document number that is not
     *         scoped to a tenant is a document number two tenants can both hold
     * @throws org.springframework.transaction.IllegalTransactionStateException when called with no
     *         transaction open (see the {@code MANDATORY} note above)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(Long orgId, String docType) {
        if (orgId == null) {
            throw new IllegalArgumentException("A document number needs an organisation.");
        }

        // THE SERIALISATION POINT, and it runs FIRST. The UPDATE takes an exclusive row lock held until the
        // caller commits, so a second till waits here instead of reading a stale maximum.
        //
        // ⚠ Doing this the other way round — ensure the row exists, then update it — DEADLOCKS, and did:
        // INSERT IGNORE on an existing key takes a shared lock, the UPDATE then needs an exclusive one, and
        // concurrent callers deadlock upgrading against each other. Update-first has no upgrade to deadlock
        // on. The concurrency test found this within a minute of being written.
        // ⚠ EXISTENCE IS CHECKED WITH A PLAIN READ, BEFORE ANYTHING TAKES A LOCK. This ordering is the third
        // and final shape of this method, and the two before it were both broken:
        //
        //   1. INSERT IGNORE then UPDATE      -> DEADLOCK. The insert takes a shared lock on an existing row,
        //                                        the update needs exclusive, concurrent callers deadlock
        //                                        upgrading against each other.
        //   2. UPDATE then create-if-missing  -> LOCK WAIT TIMEOUT, deterministically, on a tenant's FIRST
        //                                        document. An UPDATE matching ZERO rows still takes a GAP
        //                                        LOCK, so the caller's own transaction blocked the separate
        //                                        connection that was trying to create the row. Fifty seconds,
        //                                        then failure, every first-ever allocation.
        //
        // A non-locking consistent read takes no gap lock, so the counter can be created before the caller's
        // transaction holds anything. Two callers both seeing null is harmless — INSERT IGNORE settles it.
        if (repo.current(orgId, docType) == null) {
            self().ensureCounter(orgId, docType);
        }

        // THE SERIALISATION POINT. The row exists by now, so this is a plain exclusive row lock on an
        // existing row — no gap, no upgrade — held until the caller commits. A second till waits here
        // instead of reading a stale maximum.
        if (repo.bump(orgId, docType) == 0) {
            throw new IllegalStateException(
                    "Document counter vanished for org " + orgId + " / " + docType);
        }

        Long allocated = repo.current(orgId, docType);
        if (allocated == null) {
            // Cannot happen — the counter was created above. Refuse loudly rather than return 0 and let a
            // document be numbered zero.
            throw new IllegalStateException(
                    "Document counter vanished for org " + orgId + " / " + docType);
        }
        return allocated;
    }

    /**
     * Create the counter row at zero if it does not exist, in its OWN transaction, committed immediately.
     *
     * <p>Idempotent by {@code INSERT IGNORE}: several tills issuing a tenant's very first credit note at the
     * same moment all call this, one inserts, the rest are no-ops, and none of them holds a lock afterwards.
     * Allocates no number, so committing separately cannot create a gap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCounter(Long orgId, String docType) {
        repo.createCounterAtZero(orgId, docType);
    }
}
