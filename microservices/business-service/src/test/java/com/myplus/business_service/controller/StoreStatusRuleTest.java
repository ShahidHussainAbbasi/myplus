package com.myplus.business_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.Store;

/**
 * L15 — a store can be deactivated, and a deactivated store is no longer offered as somewhere to work.
 *
 * <p>Each case asserts what the defect broke: the switcher listed four INACTIVE stores (isOffered), and the
 * only way to close a store was a console command that would happily close the one you were selling from.
 */
class StoreStatusRuleTest {

    private static Store store(long id, String status) {
        Store s = new Store();
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    @Test
    void anInactiveStoreIsNotOffered_inAnyCase() {
        assertFalse(StoreController.isOffered(store(1, "INACTIVE")));
        assertFalse(StoreController.isOffered(store(1, "inactive")));
    }

    @Test
    void anActiveOrLegacyNullStoreIsOffered() {
        assertTrue(StoreController.isOffered(store(1, "ACTIVE")));
        // A row from before the column had a default reads as active — the entity's own default.
        assertTrue(StoreController.isOffered(store(1, null)));
    }

    @Test
    void anotherStoreMayBeDeactivatedAndReactivated() {
        assertNull(StoreController.refuseStatusChange(store(7, "ACTIVE"), "INACTIVE", 3L));
        assertNull(StoreController.refuseStatusChange(store(7, "INACTIVE"), "ACTIVE", 3L));
        assertNull(StoreController.refuseStatusChange(store(7, "ACTIVE"), " inactive ", 3L));
    }

    @Test
    void theStoreYouAreWorkingInCannotBeDeactivated() {
        String why = StoreController.refuseStatusChange(store(3, "ACTIVE"), "INACTIVE", 3L);
        assertNotNull(why);
        assertTrue(why.contains("Switch to another store"), why);
        // Re-activating it is never the hazard.
        assertNull(StoreController.refuseStatusChange(store(3, "INACTIVE"), "ACTIVE", 3L));
    }

    @Test
    void aCallerWithNoActiveStoreMayDeactivateAny() {
        assertNull(StoreController.refuseStatusChange(store(3, "ACTIVE"), "INACTIVE", null));
    }

    @Test
    void anUnknownStatusIsRefused_soATypoCannotReadAsActive() {
        assertEquals("Status must be ACTIVE or INACTIVE.",
                StoreController.refuseStatusChange(store(3, "ACTIVE"), "Inactiv", null));
        assertNotNull(StoreController.refuseStatusChange(store(3, "ACTIVE"), "", null));
    }
}
