package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.myplus.clinical.config.AppointmentDirectoryClient.Doctor;
import com.myplus.clinical.entity.ProviderDay;
import com.myplus.clinical.entity.QueueStatus;

/** HMS S2 — limits, labels, letters and the state machine's table. */
class QueueRulesTest {

    private static Doctor doctor(String type, Integer value) {
        return Doctor.builder().appointmentOfferType(type).appointmentOfferValue(value).timeIn("09:00").timeOut("13:00").build();
    }

    @Test
    void the_usual_limit_reads_like_appointment_service_and_blank_or_zero_is_no_limit() {
        assertThat(QueueRules.usualLimit(doctor("count", 20))).isEqualTo(20);
        assertThat(QueueRules.usualLimit(doctor("minutes", 15))).isEqualTo(16);   // 4 hours / 15 min
        assertThat(QueueRules.usualLimit(doctor("count", null))).isNull();
        assertThat(QueueRules.usualLimit(doctor("count", 0))).isNull();
    }

    @Test
    void a_one_day_change_wins_and_zero_means_no_limit_that_day() {
        ProviderDay d = new ProviderDay();
        assertThat(QueueRules.todayLimit(20, null)).isEqualTo(20);
        d.setCap(5);
        assertThat(QueueRules.todayLimit(20, d)).isEqualTo(5);
        d.setCap(0);
        assertThat(QueueRules.todayLimit(20, d)).isNull();
        d.setCap(null);
        assertThat(QueueRules.todayLimit(20, d)).isEqualTo(20);
    }

    @Test
    void labels_and_letters() {
        assertThat(QueueRules.label("A", 7)).isEqualTo("A-007");
        assertThat(QueueRules.nextPrefix(Set.of())).isEqualTo("A");
        assertThat(QueueRules.nextPrefix(Set.of("A", "B"))).isEqualTo("C");
        java.util.Set<String> all = new java.util.HashSet<>();
        for (char c = 'A'; c <= 'Z'; c++) all.add(String.valueOf(c));
        assertThat(QueueRules.nextPrefix(all)).isEqualTo("AA");
    }

    @Test
    void the_counter_key_fits_the_16_character_column() {
        assertThat(QueueRules.counterKey(12345678L, LocalDate.of(2026, 10, 9))).isEqualTo("Q261009-12345678").hasSize(16);
    }

    @Test
    void the_state_machine_allows_only_the_drawn_moves() {
        assertThat(QueueStatus.MOVES.get("call").from()).containsExactly(QueueStatus.WAITING);
        assertThat(QueueStatus.MOVES.get("complete").from()).containsExactly(QueueStatus.IN_CONSULTATION);
        assertThat(QueueStatus.MOVES.get("cancel").from()).doesNotContain(QueueStatus.IN_CONSULTATION, QueueStatus.COMPLETED);
        assertThat(QueueStatus.MOVES.values()).noneMatch(m -> m.from().contains(QueueStatus.COMPLETED));
    }
}
