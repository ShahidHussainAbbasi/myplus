package com.myplus.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** DR-1 — every spelling the mobile validator accepts must give the same key. */
class PartyKeysTest {

    @Test
    void everyAcceptedSpellingOfOneMobileIsOneKey() {
        String k = PartyKeys.phoneKey("03001234567");
        assertEquals("3001234567", k);
        assertEquals(k, PartyKeys.phoneKey("0300-1234567"));
        assertEquals(k, PartyKeys.phoneKey("+923001234567"));
        assertEquals(k, PartyKeys.phoneKey("00923001234567"));
        assertEquals(k, PartyKeys.phoneKey(" +92 300 1234567 "));
    }

    @Test
    void aShortOrEmptyNumberIsNoKey() {
        assertNull(PartyKeys.phoneKey(null));
        assertNull(PartyKeys.phoneKey(""));
        assertNull(PartyKeys.phoneKey("123456"));
    }

    @Test
    void cnicAndNtnAreDigitsOnly() {
        assertEquals("3520112345678", PartyKeys.taxKey("35201-1234567-8"));
        assertEquals("1234567", PartyKeys.taxKey("1234567"));
        assertNull(PartyKeys.taxKey("12-34"));
        assertNull(PartyKeys.taxKey("  "));
    }

    @Test
    void emailIsTrimmedAndLowerCased() {
        assertEquals("office@abc.pk", PartyKeys.emailKey("  Office@ABC.pk "));
        assertNull(PartyKeys.emailKey(" "));
    }

    @Test
    void onlyTwoPresentDifferentKeysConflict() {
        assertTrue(PartyKeys.conflict("a", "b"));
        assertFalse(PartyKeys.conflict("a", "a"));
        assertFalse(PartyKeys.conflict(null, "b"));
        assertFalse(PartyKeys.conflict("a", null));
    }
}
