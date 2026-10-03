package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessDayCalendarTest {

    // 2026-10-05 is a Monday
    private static final LocalDate MON = LocalDate.of(2026, 10, 5);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Test
    @DisplayName("[MKT-R15.1] delivered Monday → T+1 is Tuesday")
    void monday() {
        assertThat(BusinessDayCalendar.saturdaySundayWeekend(Set.of()).plusBusinessDays(MON, 1))
                .isEqualTo(MON.plusDays(1));
    }

    @Test
    @DisplayName("[MKT-R15.1] delivered Friday → T+1 is Monday, not Saturday")
    void fridayCrossesWeekend() {
        assertThat(BusinessDayCalendar.saturdaySundayWeekend(Set.of()).plusBusinessDays(FRI, 1))
                .isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("[MKT-R15.1] a bank holiday on Monday moves T+1 to Tuesday")
    void holiday() {
        BusinessDayCalendar cal = BusinessDayCalendar.saturdaySundayWeekend(Set.of(LocalDate.of(2026, 10, 12)));
        assertThat(cal.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    @DisplayName("[MKT-R15.1] T+0 is the trigger date itself")
    void tPlusZero() {
        assertThat(BusinessDayCalendar.saturdaySundayWeekend(Set.of()).plusBusinessDays(FRI, 0)).isEqualTo(FRI);
    }
}
