package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * HMS H3a (ruling L-3) — the pharmacist permission and the clinic sets are offered only where they mean something,
 * and a login being created may be put on the Doctor set — and on no other named set.
 */
class PharmacistAndDoctorSetsTest {

    @Test
    void a_shop_without_clinic_or_prescriptions_sees_neither_clinic_codes_nor_the_pharmacist_code() {
        assertThat(PermissionVisibility.codeVisible("clinic.consult", false, false)).isFalse();
        assertThat(PermissionVisibility.codeVisible("rx.dispense", false, false)).isFalse();
        assertThat(PermissionVisibility.codeVisible("sale.create", false, false)).isTrue();
        assertThat(PermissionVisibility.setVisible("Doctor", true, false)).isFalse();
        assertThat(PermissionVisibility.setVisible("Clinic reception", true, false)).isFalse();
        assertThat(PermissionVisibility.setVisible("Standard", true, false)).isTrue();
        // RW-1: the new pharmacy and stock sets
        assertThat(PermissionVisibility.setVisible("Pharmacist", true, false, false)).isFalse();
        assertThat(PermissionVisibility.setVisible("Pharmacist", true, false, true)).isTrue();
        assertThat(PermissionVisibility.setVisible("Pharmacist", true, true, false)).isTrue();
        assertThat(PermissionVisibility.setVisible("Store keeper", true, false, false)).isTrue();
        assertThat(PermissionVisibility.codeVisible("clinic.token.issue", false, true)).isFalse();
        assertThat(PermissionVisibility.codeVisible("clinic.token.issue", true, false)).isTrue();
    }

    @Test
    void a_pharmacy_sees_the_pharmacist_code_and_a_clinic_sees_everything() {
        assertThat(PermissionVisibility.codeVisible("rx.dispense", false, true)).isTrue();
        assertThat(PermissionVisibility.codeVisible("clinic.consult", false, true)).isFalse();
        assertThat(PermissionVisibility.codeVisible("clinic.consult", true, false)).isTrue();
        assertThat(PermissionVisibility.codeVisible("rx.dispense", true, false)).isTrue();
        assertThat(PermissionVisibility.setVisible("Doctor", true, true)).isTrue();
    }

    @Test
    void a_tenants_own_set_named_doctor_is_never_hidden() {
        assertThat(PermissionVisibility.setVisible("Doctor", false, false)).isTrue();
    }

    @Test
    void only_the_doctor_set_may_be_named_when_a_login_is_created() {
        assertThat(AuthService.firstSetChecked(null, "USER")).isNull();
        assertThat(AuthService.firstSetChecked("  ", "USER")).isNull();
        assertThat(AuthService.firstSetChecked("Doctor", "USER")).isEqualTo("Doctor");
        assertThatThrownBy(() -> AuthService.firstSetChecked("Administrator", "USER"))
                .hasMessageContaining("Only the Doctor set can be chosen");
        assertThatThrownBy(() -> AuthService.firstSetChecked("Doctor", "ADMIN"))
                .hasMessageContaining("member login, not an admin");
    }
}
