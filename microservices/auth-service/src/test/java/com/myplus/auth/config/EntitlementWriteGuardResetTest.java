package com.myplus.auth.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.common.settings.Capability;
import com.myplus.common.settings.EntitlementSource;

/**
 * UI-CFG-1 / SET-GUIDE — what the plan ceiling allows a RESET of a capability to do: always allowed.
 *
 * <p>A reset REMOVES the tenant's own override and returns it to exactly the state of a tenant who never touched the
 * switch. The read path (E1) subtracts only revocations, never the plan — so "on from the business type, not in the
 * plan" is simply what an untouched tenant has, and a reset grants nothing such a tenant does not already hold.
 *
 * <p>The first version refused a reset that "would switch on a feature outside the plan". That made a ONE-WAY TRAP
 * (found by a parallel review on org 13, FREE plan, General type): the capability read ON; the owner switched it off
 * (allowed — nothing is enabled); switching it back on was refused (not in plan); and Reset was refused too. The owner
 * could never get back a feature they had had. Switching ON by hand is still bounded by the plan (the write check).
 */
class EntitlementWriteGuardResetTest {

    private static final Long ORG = 13L;
    private static final Capability CAP = Capability.ORDER_TYPES;

    private boolean revoked;
    private boolean grantable;
    private EntitlementWriteGuard guard;

    @BeforeEach
    void setUp() {
        EntitlementSource ceiling = new EntitlementSource() {
            @Override public boolean grantable(Long o, Capability c) { return grantable; }
            @Override public boolean revoked(Long o, Capability c) { return revoked; }
        };
        guard = new EntitlementWriteGuard(ceiling);
    }

    @Test
    void the_one_way_trap_is_closed__off_then_Reset_brings_the_feature_back() {
        revoked = false; grantable = false;                      // FREE plan, preset grants it, no licensing row
        assertDoesNotThrow(() -> guard.check(ORG, CAP.settingKey(), "false"), "switching it off is allowed");
        assertThrows(IllegalArgumentException.class, () -> guard.check(ORG, CAP.settingKey(), "true"),
                "switching it on BY HAND is still bounded by the plan");
        assertDoesNotThrow(() -> guard.checkReset(ORG, CAP.settingKey(), "true"),
                "but Reset — back to the untouched state — must always be possible");
    }

    @Test
    void a_SUSPENDED_capability_can_be_reset__the_ceiling_keeps_it_off() {
        revoked = true; grantable = false;
        assertDoesNotThrow(() -> guard.checkReset(ORG, CAP.settingKey(), "true"));
    }

    @Test
    void a_reset_within_the_plan_is_allowed() {
        revoked = false; grantable = true;
        assertDoesNotThrow(() -> guard.checkReset(ORG, CAP.settingKey(), "true"));
    }

    @Test
    void a_WRITE_is_judged_exactly_as_before() {
        revoked = false; grantable = false;
        assertThrows(IllegalArgumentException.class, () -> guard.check(ORG, CAP.settingKey(), "true"));
        assertDoesNotThrow(() -> guard.check(ORG, CAP.settingKey(), "false"));
        revoked = true;
        assertThrows(IllegalArgumentException.class, () -> guard.check(ORG, CAP.settingKey(), "true"),
                "switching ON a suspended capability is still refused");
    }

    @Test
    void a_non_capability_key_is_not_this_guards_business() {
        assertDoesNotThrow(() -> guard.checkReset(ORG, "pos.park.enabled", "true"));
    }
}
