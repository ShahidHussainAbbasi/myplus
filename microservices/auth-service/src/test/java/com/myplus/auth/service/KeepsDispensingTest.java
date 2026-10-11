package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * B2 (release readiness 2026-10-11) — nobody who dispensed yesterday is refused after H3 makes pharma require
 * rx.dispense. A business member on NO set holds role privileges only; the role's write privilege is what let them
 * dispense before, so it still does. Everyone else is decided by their set (V21 built-ins, V22 custom sets).
 */
class KeepsDispensingTest {

    private static final List<String> WRITER = List.of("LOGIN_PRIVILEGE", "READ_PRIVILEGE", "WRITE_PRIVILEGE");
    private static final List<String> READER = List.of("LOGIN_PRIVILEGE", "READ_PRIVILEGE");

    @Test
    void a_shop_member_on_no_set_who_can_write_keeps_dispensing() {
        assertThat(PermissionService.keepsDispensingWithoutASet("BUSINESS", false, WRITER, null)).isTrue();
    }

    @Test
    void a_member_on_a_set_gets_what_the_set_says_not_this_fallback() {
        // e.g. "Clinic reception": the owner chose a set without rx.dispense — the fallback must not undo that
        assertThat(PermissionService.keepsDispensingWithoutASet("BUSINESS", false, WRITER, 42L)).isFalse();
    }

    @Test
    void a_member_who_could_not_write_yesterday_gains_nothing() {
        assertThat(PermissionService.keepsDispensingWithoutASet("BUSINESS", false, READER, null)).isFalse();
    }

    @Test
    void another_module_never_gets_a_pharmacy_code() {
        assertThat(PermissionService.keepsDispensingWithoutASet("EDUCATION", false, WRITER, null)).isFalse();
        assertThat(PermissionService.keepsDispensingWithoutASet(null, false, WRITER, null)).isFalse();
    }

    @Test
    void an_owner_is_not_decided_here_they_hold_everything_already() {
        assertThat(PermissionService.keepsDispensingWithoutASet("BUSINESS", true, WRITER, null)).isFalse();
    }
}
