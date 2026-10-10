package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.myplus.common.web.exception.ValidationException;

/** HMS S3a — vital signs a person can have; a typo is refused with the range, never stored. */
class EncounterRulesTest {

    @Test
    void blood_pressure_is_systolic_over_diastolic() {
        assertThat(EncounterRules.bloodPressure("120/80")).containsExactly(120, 80);
        assertThat(EncounterRules.bloodPressure(" 135 / 85 ")).containsExactly(135, 85);
        assertThat(EncounterRules.bloodPressure("")).isNull();
        assertThatThrownBy(() -> EncounterRules.bloodPressure("12080")).hasMessageContaining("e.g. 120/80");
        assertThatThrownBy(() -> EncounterRules.bloodPressure("80/120")).hasMessageContaining("lower one");
        assertThatThrownBy(() -> EncounterRules.bloodPressure("400/80")).hasMessageContaining("between 50 and 260");
    }

    @Test
    void a_temperature_of_986_is_a_typo_not_a_reading() {
        assertThat(EncounterRules.temperatureF("98.6")).isEqualByComparingTo(new BigDecimal("98.6"));
        assertThatThrownBy(() -> EncounterRules.temperatureF("986")).isInstanceOf(ValidationException.class)
                .hasMessage("Temperature (°F) must be between 90 and 110.");
        assertThatThrownBy(() -> EncounterRules.temperatureF("hot")).hasMessageContaining("not a number");
    }

    @Test
    void pulse_spo2_weight_and_height_have_human_ranges() {
        assertThat(EncounterRules.pulse("78")).isEqualTo(78);
        assertThatThrownBy(() -> EncounterRules.pulse("780")).hasMessageContaining("between 20 and 250");
        assertThat(EncounterRules.spo2("98")).isEqualTo(98);
        assertThatThrownBy(() -> EncounterRules.spo2("101")).hasMessageContaining("between 50 and 100");
        assertThat(EncounterRules.weightKg("72.5")).isEqualByComparingTo("72.5");
        assertThatThrownBy(() -> EncounterRules.heightCm("2")).hasMessageContaining("between 30 and 250");
        assertThat(EncounterRules.pulse(" ")).isNull();
    }

    @Test
    void a_note_needs_words_and_fits_its_column() {
        assertThat(EncounterRules.note("  Throat congested.  ")).isEqualTo("Throat congested.");
        assertThatThrownBy(() -> EncounterRules.note(" ")).hasMessageContaining("Write the note");
        assertThatThrownBy(() -> EncounterRules.note("x".repeat(4001))).hasMessageContaining("at most 4000");
    }
}
