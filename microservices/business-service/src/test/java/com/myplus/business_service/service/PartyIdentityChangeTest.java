package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * DR-1 — an edit re-links a customer or supplier to a partner only when a key it is MATCHED on changed. Re-typing the
 * same number in another format, or changing the name or address, is not a change of partner.
 */
class PartyIdentityChangeTest {

    private static boolean changed(String p0, String p1, String e0, String e1, String t0, String t1) {
        return PartyBridgeService.identityChanged(p0, p1, e0, e1, t0, t1);
    }

    @Test
    void retypingTheSameNumberIsNotAChange() {
        assertFalse(changed("0300-1234567", "+923001234567", "a@x.pk", " A@X.pk ", "35201-1234567-8", "3520112345678"));
    }

    @Test
    void aNewNumberIsAChange() {
        assertTrue(changed("03001234567", "03119876543", null, null, null, null));
    }

    @Test
    void aNewEmailOrCnicIsAChange() {
        assertTrue(changed("03001234567", "03001234567", "a@x.pk", "b@x.pk", null, null));
        assertTrue(changed("03001234567", "03001234567", null, null, null, "35201-1234567-8"));
    }

    @Test
    void nothingToNothingIsNotAChange() {
        assertFalse(changed(null, "", null, "  ", null, "12"));
    }
}
