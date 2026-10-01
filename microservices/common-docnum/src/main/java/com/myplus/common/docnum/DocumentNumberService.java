package com.myplus.common.docnum;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The next per-org document number, allocated so that two tills cannot take the same one.
 *
 * <p>EX-0c — moved here VERBATIM from business-service (DOC-INT, V45), where finance-service (V7) had copied it.
 * One algorithm for every service that numbers documents; each keeps its own counter table behind
 * {@link DocumentCounterStore}. The service-specific document TYPES stay with the service that owns them.
 *
 * <h3>What this replaces, and why</h3>
 * {@code SELECT MAX(seq) + 1} is not an allocation, it is a guess that is usually right. Two concurrent
 * callers read the same maximum, both take it, and the UNIQUE constraint refuses the loser — whose operation
 * then failed with {@code "Transaction silently rolled back because it has been marked as rollback-only"}.
 *
 * <p>Here the number comes from a counter row, and the row lock the UPDATE takes is what makes the second
 * caller wait rather than collide. <b>The collision is prevented, not recovered from</b> — which matters
 * because the operations that need this have already touched inventory by the time they allocate, so replaying
 * them would put stock back twice.
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
 * or the lock is held across the network and one slow round trip stalls the whole tenant.
 */
public class DocumentNumberService {

    private final ObjectProvider<DocumentCounterStore> storeProvider;

    /**
     * This bean, through the proxy. {@link #ensureCounter} must run in its OWN transaction, and a plain
     * {@code this.ensureCounter(...)} would be a self-invocation — which never passes through the proxy, so
     * the annotation would be decorative and the row would be created inside the caller's transaction after
     * all. That is exactly the trap that made INST-3a's scanner and this class's first two attempts wrong.
     */
    private final ObjectProvider<DocumentNumberService> selfProvider;

    /**
     * Both collaborators are resolved LAZILY. The store is usually a Spring Data repository, whose bean
     * definition is registered by another auto-configuration; resolving on first use means the order in which
     * the two are registered cannot matter.
     */
    public DocumentNumberService(ObjectProvider<DocumentCounterStore> storeProvider,
                                 ObjectProvider<DocumentNumberService> selfProvider) {
        this.storeProvider = storeProvider;
        this.selfProvider = selfProvider;
    }

    private DocumentCounterStore store() {
        DocumentCounterStore s = storeProvider.getIfUnique();
        if (s == null) {
            throw new IllegalStateException("This service numbers documents but has no single DocumentCounterStore "
                    + "bean — its OrgDocumentSeqRepo must implement com.myplus.common.docnum.DocumentCounterStore.");
        }
        return s;
    }

    private DocumentNumberService self() {
        DocumentNumberService s = selfProvider.getIfAvailable();
        return s != null ? s : this;
    }

    /**
     * @return the number just allocated: 1 for the first document of this type in this org, then 2, 3, …
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(Long orgId, String docType) {
        if (orgId == null) {
            throw new IllegalArgumentException("A document number needs an organisation.");
        }
        DocumentCounterStore repo = store();

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
     * Create the counter at zero, in a transaction of its OWN that commits immediately, so the row is visible
     * to the caller's transaction before the caller locks it. Public only so the proxy can intercept it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCounter(Long orgId, String docType) {
        store().createCounterAtZero(orgId, docType);
    }
}
