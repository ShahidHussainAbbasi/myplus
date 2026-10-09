package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.myplus.common.web.exception.ValidationException;

/** HMS S1 — M-01 / M-01b: the phone is the only required field, so its rules are the registration's rules. */
class PatientRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"03001234567", "0300-1234567", "+923001234567", "923001234567", "00923001234567", " 0300 1234567 "})
    void every_spelling_of_one_mobile_is_one_number(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).isEqualTo("03001234567");
        assertThat(PhoneNumbers.key(PhoneNumbers.normalise(typed))).isEqualTo("3001234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "0300123", "04235761234", "abcdefghijk", "0300123456789", "02001234567", ""})
    void not_a_mobile_is_refused_in_words(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).isNull();
        assertThatThrownBy(() -> PatientRules.phone(typed)).isInstanceOf(ValidationException.class)
                .hasMessageContaining(typed.isBlank() ? "Enter the patient's mobile number" : "is not a mobile number");
    }

    @Test
    void a_blank_name_becomes_the_phone_because_only_the_phone_is_required() {
        assertThat(PatientRules.name("  ", "03001234567")).isEqualTo("03001234567");
        assertThat(PatientRules.name("  Ali   Khan ", "03001234567")).isEqualTo("Ali Khan");
    }

    @Test
    void cnic_is_optional_unless_the_clinic_requires_it_and_is_stored_formatted() {
        assertThat(PatientRules.cnic(null, false)).isNull();
        assertThat(PatientRules.cnic("4220112345671", false)).isEqualTo("42201-1234567-1");
        assertThatThrownBy(() -> PatientRules.cnic("", true)).hasMessageContaining("requires it");
        assertThatThrownBy(() -> PatientRules.cnic("12345", false)).hasMessageContaining("13 digits");
    }

    @Test
    void date_of_birth_cannot_be_in_the_future() {
        LocalDate today = LocalDate.of(2026, 10, 9);
        assertThat(PatientRules.dateOfBirth("1990-05-14", today)).isEqualTo(LocalDate.of(1990, 5, 14));
        assertThatThrownBy(() -> PatientRules.dateOfBirth("2026-10-10", today)).hasMessageContaining("future");
        assertThatThrownBy(() -> PatientRules.dateOfBirth("14/05/1990", today)).hasMessageContaining("not a date");
    }

    @Test
    void mrn_carries_the_clinic_code_else_the_org_and_the_year() {
        LocalDate d = LocalDate.of(2026, 10, 9);
        assertThat(PatientRules.mrn("ISB", 24L, d, 123)).isEqualTo("MRN-ISB-26-000123");
        assertThat(PatientRules.mrn("", 24L, d, 1)).isEqualTo("MRN-24-26-000001");
    }
}
