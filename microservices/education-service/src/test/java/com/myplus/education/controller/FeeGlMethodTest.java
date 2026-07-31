package com.myplus.education.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Slice 0.1 — the "Received In" → finance method translation.
 *
 * This exists because of a specific, easy-to-miss trap: finance's {@code cashAccount()} routes to the Bank
 * account with {@code startsWith("CHEQUE")}, while this module stores the literal {@code "Check"}. Passing the
 * raw value through would silently post every cheque to Cash — books that balance but are wrong, which no
 * balance-based assertion would ever catch.
 *
 * Pure reflection over the private helper: no Spring, no DB, no Docker, so it runs on every {@code mvn test}.
 */
class FeeGlMethodTest {

    private static String glMethod(String receivedIn) throws Exception {
        Method m = FeeCollectionController.class.getDeclaredMethod("glMethod", String.class);
        m.setAccessible(true);
        return (String) m.invoke(new FeeCollectionController(), receivedIn);
    }

    @Test @DisplayName("Check → CHEQUE, so finance routes it to Bank and not Cash")
    void checkMapsToCheque() throws Exception {
        assertEquals("CHEQUE", glMethod("Check"));
        assertEquals("CHEQUE", glMethod("check"));
        assertEquals("CHEQUE", glMethod("  Cheque  "));
    }

    @Test @DisplayName("Cash → CASH")
    void cashMapsToCash() throws Exception {
        assertEquals("CASH", glMethod("Cash"));
        assertEquals("CASH", glMethod("cash"));
    }

    @Test @DisplayName("A missing or unrecognised value falls back to CASH, never null")
    void unknownFallsBackToCash() throws Exception {
        // Null must not reach finance: cashAccount(null) would work, but an explicit CASH keeps the outbox row
        // self-describing for anyone reading the ledger trail later.
        assertEquals("CASH", glMethod(null));
        assertEquals("CASH", glMethod(""));
        assertEquals("CASH", glMethod("Online"));
    }
}
