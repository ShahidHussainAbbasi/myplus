package com.myplus.common.subledger;

import java.math.BigDecimal;

/**
 * One still-owing source document a payment can be allocated against — an AR invoice (CustomerHistory) or an AP
 * purchase bill (Purchase), and any future vertical's doc (education fee, welfare pledge…). The owning service
 * adapts its entity to this interface; {@link SubledgerService#settle} then allocates against it generically,
 * so the FIFO + ledger logic lives in ONE place (no per-vertical duplication).
 */
public interface OpenDoc {

    /** Positive amount still owed on this document (0 when settled). */
    BigDecimal outstanding();

    /** Record {@code amount} as paid against this document (bump paid, move due toward 0) and persist it. */
    void apply(BigDecimal amount);

    /** Ledger allocation descriptors — the doc kind ("INVOICE" | "PURCHASE"), its id and its human number. */
    String docType();
    Long docId();
    String docNo();
}
