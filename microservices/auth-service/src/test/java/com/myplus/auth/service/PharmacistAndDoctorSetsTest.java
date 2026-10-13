package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * RW-0a (2026-10-12) — what the Team screen offers is decided by the ROWS' capability columns (V24), not by names in
 * code; and a login being created may ask only for a JOB (the doctor's), never a set by name.
 * (Replaces the H3a name-based checks of this class.)
 */
class PharmacistAndDoctorSetsTest {

    private static Set<String> on(String... codes) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("clinic", false); m.put("rxRequired", false); m.put("batchTracking", true);
        for (String c : codes) m.put(c, true);
        return PermissionVisibility.enabled(m);
    }

    @Test
    void a_row_with_no_capability_is_always_offered() {
        assertThat(PermissionVisibility.visible(null, on())).isTrue();
        assertThat(PermissionVisibility.visible(" ", on())).isTrue();
    }

    @Test
    void a_row_needing_the_clinic_is_offered_only_with_the_clinic_on() {
        assertThat(PermissionVisibility.visible("clinic", on())).isFalse();
        assertThat(PermissionVisibility.visible("clinic", on("clinic"))).isTrue();
    }

    @Test
    void a_row_listing_several_settings_is_offered_when_any_one_is_on() {
        // e.g. rx.dispense / the Pharmacist set: 'clinic,rxRequired'
        assertThat(PermissionVisibility.visible("clinic,rxRequired", on())).isFalse();
        assertThat(PermissionVisibility.visible("clinic,rxRequired", on("rxRequired"))).isTrue();
        assertThat(PermissionVisibility.visible("clinic, rxRequired", on("rxRequired"))).isTrue();
    }

    @Test
    void only_the_settings_switched_on_count() {
        assertThat(PermissionVisibility.enabled(null)).isEmpty();
        assertThat(on()).containsExactly("batchTracking");
    }

    @Test
    void a_workspace_opens_for_any_code_it_lists_and_always_for_the_owner() {
        // RW-3: the front desk's row lists 'clinic.patient.edit,clinic.token.issue'
        assertThat(PermissionVisibility.opens("clinic.patient.edit,clinic.token.issue", Set.of("clinic.token.issue"), false)).isTrue();
        assertThat(PermissionVisibility.opens("clinic.consult", Set.of("sale.create"), false)).isFalse();
        assertThat(PermissionVisibility.opens("clinic.consult", Set.of(), true)).isTrue();
        assertThat(PermissionVisibility.opens(null, Set.of(), false)).isTrue();
    }

    @Test
    void a_login_being_created_may_ask_only_for_the_doctors_job() {
        assertThat(AuthService.firstSetChecked(null, "USER")).isNull();
        assertThat(AuthService.firstSetChecked("  ", "USER")).isNull();
        assertThat(AuthService.firstSetChecked("DOCTOR", "USER")).isEqualTo(PermissionService.JOB_DOCTOR);
        // an older monolith sends "Doctor" — the same job
        assertThat(AuthService.firstSetChecked("Doctor", "USER")).isEqualTo(PermissionService.JOB_DOCTOR);
        assertThatThrownBy(() -> AuthService.firstSetChecked("Administrator", "USER"))
                .hasMessageContaining("Only the doctor's job can be chosen");
        assertThatThrownBy(() -> AuthService.firstSetChecked("Doctor", "ADMIN"))
                .hasMessageContaining("member login, not an admin");
    }
}
