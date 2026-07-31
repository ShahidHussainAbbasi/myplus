package com.myplus.common.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The shared rules for party credit — money a tenant holds on a party's behalf (POS store credit for a customer,
 * school fee credit for a student).
 *
 * Three rules live here precisely because a second implementation gets them subtly wrong:
 *
 *   1. the ledger is APPEND-ONLY and SIGNED — a balance is the sum of its history, so it always explains itself;
 *   2. a redemption is CAPPED at the current balance — credit can never overdraw into an unbacked liability;
 *   3. the cached balance is REFRESHED after every write — a stale cache silently misprices the next transaction.
 *
 * Storage is the {@link CreditStore} SPI, so this class holds no table, no entity and no tenant logic. What
 * differs per domain is only how credit is SPENT: POS offers it as a tender the cashier selects, education
 * applies it automatically to the next charge. That choice belongs to the caller, not here.
 */
public class CreditService {

    private final CreditStore store;

    public CreditService(CreditStore store) {
        this.store = store;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static BigDecimal scale(BigDecimal v) { return nz(v).setScale(2, RoundingMode.HALF_UP); }

    /** The party's current credit balance (never null; zero when they hold none). */
    public BigDecimal balance(Long partyId) {
        if (partyId == null) return BigDecimal.ZERO;
        return scale(store.balance(partyId));
    }

    /**
     * Issue credit (+balance).
     *
     * @return the amount issued, or zero when there was nothing to issue
     */
    public BigDecimal issue(Long partyId, BigDecimal amount, String reason, String ref) {
        BigDecimal amt = scale(amount);
        if (partyId == null || amt.signum() <= 0) return BigDecimal.ZERO;
        write(partyId, amt, reason != null ? reason : "ISSUE", ref);
        return amt;
    }

    /**
     * Redeem credit (−balance), CAPPED at what the party actually holds.
     *
     * Returning the amount actually taken (rather than throwing on a shortfall) is deliberate: a caller normally
     * wants "use whatever credit exists, then bill the rest", and capping here means no caller can overdraw by
     * forgetting to check first.
     *
     * @return the amount actually redeemed — may be less than requested, or zero
     */
    public BigDecimal redeem(Long partyId, BigDecimal amount, String ref) {
        BigDecimal want = scale(amount);
        if (partyId == null || want.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal take = want.min(balance(partyId));
        if (take.signum() <= 0) return BigDecimal.ZERO;
        write(partyId, take.negate(), "REDEEM", ref);
        return take;
    }

    /** Append the movement, then refresh the cached balance — never one without the other. */
    private void write(Long partyId, BigDecimal signedAmount, String reason, String ref) {
        store.append(partyId, scale(signedAmount), reason, ref);
        store.cacheBalance(partyId, balance(partyId));
    }
}
