package com.myplus.common.security.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H6 C3 — the accounting day of a recorded instant is the BUSINESS's day (Asia/Karachi), never the UTC day. */
class BusinessDayTest {

    @BeforeEach
    void karachi() { TenantClock.configureDefaultZone("Asia/Karachi"); }

    @AfterEach
    void reset() { TenantClock.configureDefaultZone("Asia/Karachi"); }

    @Test
    void a_purchase_recorded_at_02_00_karachi_belongs_to_that_karachi_day_not_the_utc_day_before() {
        LocalDateTime recorded = LocalDateTime.of(2026, 10, 10, 21, 0);   // 02:00 on the 11th in Karachi
        assertThat(recorded.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 10));   // the defect: UTC day
        assertThat(BusinessDay.of(recorded)).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void a_typed_bill_date_stored_as_utc_midnight_keeps_its_day() {
        // the 90 typed purchases: a typed day held as UTC midnight (DB 05:00 Karachi) — still that day
        assertThat(BusinessDay.of(LocalDateTime.of(2026, 10, 1, 0, 0))).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void a_business_day_runs_19_00_utc_the_day_before_to_18_59_59_utc() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        assertThat(BusinessDay.start(d)).isEqualTo(LocalDateTime.of(2026, 10, 10, 19, 0));
        assertThat(BusinessDay.end(d)).isEqualTo(LocalDateTime.of(2026, 10, 11, 18, 59, 59, 999_999_999));
    }

    @Test
    void a_same_day_range_is_a_whole_day_never_empty() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        assertThat(BusinessDay.end(d)).isAfter(BusinessDay.start(d).plusHours(23));
        assertThat(LocalDateTime.of(2026, 10, 10, 21, 0)).isBetween(BusinessDay.start(d), BusinessDay.end(d));
    }

    @Test
    void a_typed_report_bound_is_business_wall_time() {
        assertThat(BusinessDay.fromBusinessTime(LocalDateTime.of(2026, 10, 11, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 10, 19, 0));
    }

    @Test
    void the_viewers_zone_never_changes_the_business_day() {
        // TenantClock.today() follows the viewer (TZ-2); the business day must not
        assertThat(TenantClock.businessZone().getId()).isEqualTo("Asia/Karachi");
        assertThat(BusinessDay.of(LocalDateTime.of(2026, 10, 10, 21, 0))).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void null_in_null_out() {
        assertThat(BusinessDay.of(null)).isNull();
        assertThat(BusinessDay.start(null)).isNull();
        assertThat(BusinessDay.end(null)).isNull();
        assertThat(BusinessDay.fromBusinessTime(null)).isNull();
    }
}
