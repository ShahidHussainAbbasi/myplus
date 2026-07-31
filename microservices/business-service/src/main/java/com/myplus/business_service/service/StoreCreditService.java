package com.myplus.business_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Store credit (SF-5 Model B). Issues/redeems store credit against the ledger and keeps the customer's cached
 * {@code creditBalance} in sync (= Σ ledger, tenant-scoped). {@code redeem} never lets the balance go negative
 * (over-redemption is rejected). Every mutation is one row so a void can reverse it (issue↔redeem).
 */
@Service
@RequiredArgsConstructor
public class StoreCreditService {

    /**
     * Slice 0.2b: the ledger RULES now live in common-credit (append-only, redeem capped at the balance,
     * cache refreshed after every write) so POS and school fee credit cannot drift apart on them. Storage is
     * still POS's own — {@code store_credit_txn} + {@code Customer.creditBalance} via {@link
     * com.myplus.business_service.config.JpaStoreCreditStore}.
     *
     * This class is kept as POS's façade so existing callers (checkout tender, returns, void) are untouched.
     */
    private final com.myplus.common.credit.CreditService credit;

    /** The customer's current store-credit balance (tenant-scoped). */
    public BigDecimal balance(Long customerId) {
        return credit.balance(customerId);
    }

    /** Issue store credit (+balance). Returns the amount issued (0 if amount <= 0). */
    public BigDecimal issue(Long customerId, BigDecimal amount, String reason, String ref) {
        return credit.issue(customerId, amount, reason != null ? reason : "RETURN", ref);
    }

    /** Redeem store credit (-balance), capped at the current balance. Returns the amount actually redeemed. */
    public BigDecimal redeem(Long customerId, BigDecimal amount, String ref) {
        return credit.redeem(customerId, amount, ref);
    }

    /** Kept for callers that force a cache refresh; the shared service already does this after every write. */
    public void recomputeCredit(Long customerId) {
        credit.balance(customerId);
    }
}
