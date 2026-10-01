package com.myplus.common.docnum;

/**
 * EX-0c — where a service keeps its per-org document counters: one row per {@code (organization_id, doc_type)}
 * in its OWN database's {@code org_document_seq} table.
 *
 * <p>The port of a Ports-and-Adapters split, the same shape as {@code common-credit}'s {@code CreditStore}: the
 * allocation RULE lives once in {@link DocumentNumberService}, the DATA stays local. Each service's existing
 * {@code OrgDocumentSeqRepo} implements this directly — its three native statements already have exactly these
 * signatures, so adopting the library changes no SQL.
 *
 * <p>The contract each method must keep is the one {@link DocumentNumberService} relies on; read its comments
 * before writing a new adapter. In short:
 * <ul>
 *   <li>{@link #current} is a plain, NON-locking read — it must take no gap lock;</li>
 *   <li>{@link #bump} is one {@code UPDATE … next_val = next_val + 1} on an existing row, returning rows matched;</li>
 *   <li>{@link #createCounterAtZero} is {@code INSERT IGNORE} at 0, so two callers racing to create it both succeed.</li>
 * </ul>
 */
public interface DocumentCounterStore {

    /** The last number issued for this org and document type, or null when the counter does not exist yet. */
    Long current(Long orgId, String docType);

    /** Advance the counter by one under an exclusive row lock. Returns the number of rows updated (0 or 1). */
    int bump(Long orgId, String docType);

    /** Create the counter at zero if it does not exist; a no-op when it does. */
    void createCounterAtZero(Long orgId, String docType);
}
