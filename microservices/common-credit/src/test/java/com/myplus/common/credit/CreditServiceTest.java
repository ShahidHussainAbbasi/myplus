package com.myplus.common.credit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 0.2b — the shared credit rules, now serving BOTH POS store credit and school fee credit.
 *
 * These are the rules a second implementation gets subtly wrong, which is exactly why they were extracted:
 * append-only, redeem capped at the balance, cache refreshed after every write. Pure — an in-memory
 * {@link CreditStore}, no Spring, no DB — so it runs on every {@code mvn test}.
 */
class CreditServiceTest {

    /** In-memory store: a signed ledger plus the cached balance, mirroring what both real stores persist. */
    private static class FakeStore implements CreditStore {
        final List<BigDecimal> ledger = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        BigDecimal cached;
        int cacheWrites = 0;

        public void append(Long partyId, BigDecimal signedAmount, String reason, String ref) {
            ledger.add(signedAmount);
            reasons.add(reason);
        }
        public BigDecimal balance(Long partyId) {
            return ledger.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        public void cacheBalance(Long partyId, BigDecimal balance) {
            cached = balance;
            cacheWrites++;
        }
    }

    private FakeStore store;
    private CreditService credit;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        credit = new CreditService(store);
    }

    @Test @DisplayName("Issuing raises the balance and appends one signed row")
    void issueRaisesBalance() {
        assertEquals(new BigDecimal("4000.00"), credit.issue(1L, new BigDecimal("4000"), "OVERPAYMENT", "fee-1"));
        assertEquals(new BigDecimal("4000.00"), credit.balance(1L));
        assertEquals(1, store.ledger.size());
        assertTrue(store.ledger.get(0).signum() > 0, "an issue is a POSITIVE movement");
    }

    @Test @DisplayName("Redeeming is CAPPED at the balance — credit can never overdraw")
    void redeemIsCapped() {
        credit.issue(1L, new BigDecimal("1000"), "OVERPAYMENT", "fee-1");

        BigDecimal taken = credit.redeem(1L, new BigDecimal("5000"), "fee-2");

        // The caller asked for 5000 and gets told 1000 — so it can bill the difference rather than silently
        // handing out money the school never held.
        assertEquals(new BigDecimal("1000.00"), taken);
        assertEquals(new BigDecimal("0.00"), credit.balance(1L));
        assertTrue(credit.balance(1L).signum() >= 0, "balance must never go negative");
    }

    @Test @DisplayName("Redeeming against no balance is a no-op, not a negative row")
    void redeemWithNoBalance() {
        assertEquals(BigDecimal.ZERO, credit.redeem(1L, new BigDecimal("500"), "fee-1"));
        assertEquals(0, store.ledger.size(), "nothing should be written when there is nothing to take");
    }

    @Test @DisplayName("The cached balance is refreshed after EVERY write")
    void cacheRefreshedAfterEveryWrite() {
        // A stale cache silently misprices the next transaction, so this is a rule rather than an optimisation.
        credit.issue(1L, new BigDecimal("3000"), "OVERPAYMENT", "a");
        assertEquals(new BigDecimal("3000.00"), store.cached);
        credit.redeem(1L, new BigDecimal("1000"), "b");
        assertEquals(new BigDecimal("2000.00"), store.cached);
        assertEquals(2, store.cacheWrites);
    }

    @Test @DisplayName("Zero, negative and null are ignored rather than written")
    void nonPositiveIgnored() {
        assertEquals(BigDecimal.ZERO, credit.issue(1L, BigDecimal.ZERO, "x", "r"));
        assertEquals(BigDecimal.ZERO, credit.issue(1L, new BigDecimal("-50"), "x", "r"));
        assertEquals(BigDecimal.ZERO, credit.issue(null, new BigDecimal("50"), "x", "r"));
        assertEquals(BigDecimal.ZERO, credit.redeem(null, new BigDecimal("50"), "r"));
        assertEquals(0, store.ledger.size());
    }

    @Test @DisplayName("The balance is the sum of history — issue, redeem, issue")
    void balanceIsTheSumOfHistory() {
        credit.issue(1L, new BigDecimal("5000"), "OVERPAYMENT", "a");
        credit.redeem(1L, new BigDecimal("3000"), "b");
        credit.issue(1L, new BigDecimal("500"), "OVERPAYMENT", "c");
        assertEquals(new BigDecimal("2500.00"), credit.balance(1L));
        assertEquals(3, store.ledger.size(), "append-only: three movements, none edited away");
    }
}
