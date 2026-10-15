package com.myplus.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * HMS S1 / B-07 — a booking on a known phone keeps that phone's patient: the same name (however typed) books,
 * a different name is refused instead of being filed under the earlier patient.
 */
class OnePatientPerPhoneTest {

    @Test
    void the_same_person_however_typed_is_not_a_different_name() {
        assertThat(AppointmentService.differentName("Rashid Ahmed", "rashid  ahmed ")).isFalse();
        assertThat(AppointmentService.differentName("Rashid Ahmed", "")).isFalse();
        assertThat(AppointmentService.differentName("Rashid Ahmed", null)).isFalse();
    }

    @Test
    void another_name_on_the_phone_is_a_different_person() {
        assertThat(AppointmentService.differentName("Rashid Ahmed", "Usman Rashid")).isTrue();
    }
}
