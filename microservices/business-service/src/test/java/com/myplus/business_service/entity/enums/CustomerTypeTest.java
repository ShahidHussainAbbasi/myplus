package com.myplus.business_service.entity.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * B2B-P0 — the customer channel type.
 *
 * <p>Pure logic, no Spring: this runs on every {@code mvn test}. The value of pinning it is that the
 * B2B/B2C split is DERIVED from this one field rather than stored as a second column, so a wrong
 * mapping here quietly mis-routes pricing, terms and reporting for a whole account.
 */
class CustomerTypeTest {

    @Test
    @DisplayName("the trade types are B2B, the retail types are not")
    void channelMapping() {
        assertTrue(CustomerType.RETAILER.isB2B(), "a retailer buys to resell — trade");
        assertTrue(CustomerType.WHOLESALE.isB2B(), "wholesale is trade");
        assertFalse(CustomerType.WALK_IN.isB2B(), "a walk-in shopper is retail");
        assertFalse(CustomerType.VIP.isB2B(), "VIP is a loyalty tier on a RETAIL customer, not a trade account");
    }

    @Test
    @DisplayName("an unset type resolves to WALK_IN — today's behaviour for every existing customer")
    void orDefaultFillsWalkIn() {
        assertEquals(CustomerType.WALK_IN, CustomerType.orDefault(null));
    }

    @ParameterizedTest
    @EnumSource(CustomerType.class)
    @DisplayName("orDefault never rewrites a value the caller actually supplied")
    void orDefaultIsIdentityForRealValues(CustomerType type) {
        assertEquals(type, CustomerType.orDefault(type));
    }

    @ParameterizedTest
    @EnumSource(CustomerType.class)
    @DisplayName("isB2B agrees with the declared channel for every type")
    void isB2BAgreesWithChannel(CustomerType type) {
        assertEquals(type.channel() == CustomerType.Channel.B2B, type.isB2B());
    }

    @Test
    @DisplayName("channelOf is null-safe — an unset type is B2C, never a null channel")
    void channelOfIsNullSafe() {
        assertEquals(CustomerType.Channel.B2C, CustomerType.channelOf(null));
        assertEquals(CustomerType.Channel.B2B, CustomerType.channelOf(CustomerType.WHOLESALE));
    }

    @Test
    @DisplayName("the persisted names are the contract — V29 backfilled these exact strings")
    void namesAreStable() {
        // @Enumerated(STRING) writes name() into customer_type, and V29 backfilled 'WALK_IN'. Renaming a
        // constant would orphan every existing row, so the names are pinned here deliberately.
        assertEquals("WALK_IN", CustomerType.WALK_IN.name());
        assertEquals("RETAILER", CustomerType.RETAILER.name());
        assertEquals("WHOLESALE", CustomerType.WHOLESALE.name());
        assertEquals("VIP", CustomerType.VIP.name());
        assertEquals(4, CustomerType.values().length, "a new type needs a UI option + an i18n key in all six bundles");
    }
}
