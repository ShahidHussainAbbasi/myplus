package com.myplus.common.credit;

import java.math.BigDecimal;

/**
 * Storage SPI for a party's credit ledger. Each service supplies its own table and its own cached-balance owner,
 * so no credit data is shared across services — only the rules in {@link CreditService} are.
 *
 * Implementations: business {@code store_credit_txn} + {@code Customer.creditBalance};
 * education {@code fee_credit_txn} + {@code Student.creditBalance}.
 *
 * Every method is tenant-scoped BY THE IMPLEMENTATION — the shared logic never sees an org id, so it cannot get
 * scoping wrong or accidentally read across tenants.
 */
public interface CreditStore {

    /**
     * Append one SIGNED movement: positive issues credit, negative redeems it. Append-only by contract — a
     * balance is the sum of its history, never an edited figure, so the ledger always explains itself.
     */
    void append(Long partyId, BigDecimal signedAmount, String reason, String ref);

    /** The party's current balance, summed from the ledger within the caller's tenant. */
    BigDecimal balance(Long partyId);

    /**
     * Write the freshly computed balance onto the owning entity's cached column. A cache, not a source of truth:
     * {@link #balance} remains authoritative, and this exists so a list screen need not re-sum the ledger per row.
     */
    void cacheBalance(Long partyId, BigDecimal balance);
}
