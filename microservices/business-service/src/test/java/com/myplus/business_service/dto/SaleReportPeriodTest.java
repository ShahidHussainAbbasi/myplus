package com.myplus.business_service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR-1 — the Sale Detail Report's period vocabulary.
 *
 * <p>{@code today} is a parameter rather than {@code LocalDate.now()}, so these run on the 1st of a month and
 * on the 31st alike. That matters: the defect this slice fixes was WORST on the 1st, when the old
 * current-month default showed a trading shop an empty report.
 */
class SaleReportPeriodTest {

    /** A mid-month day and a first-of-month day — the second is where the old default failed. */
    private static final LocalDate MID = LocalDate.of(2026, 9, 6);
    private static final LocalDate FIRST = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("⭐ asking for nothing means the last 30 days, not the current month")
    void defaultIsLastThirtyDays() {
        assertEquals(SaleReportPeriod.LAST_30_DAYS, SaleReportPeriod.from(null, false));
        assertEquals(SaleReportPeriod.LAST_30_DAYS, SaleReportPeriod.DEFAULT);
    }

    @Test
    @DisplayName("⭐ on the 1st of a month the default still reaches back a full month")
    void defaultOnTheFirstOfAMonth() {
        LocalDateTime[] r = SaleReportPeriod.DEFAULT.range(FIRST);
        assertEquals(LocalDate.of(2026, 8, 3), r[0].toLocalDate(), "30 days back, today included");
        assertEquals(FIRST, r[1].toLocalDate());
        // The old behaviour — this month — would have started on the 1st and shown that day alone.
        assertEquals(FIRST, SaleReportPeriod.THIS_MONTH.range(FIRST)[0].toLocalDate());
    }

    @Test
    @DisplayName("dates with no period named are a custom range, not the default")
    void datesWithoutACodeMeanCustom() {
        assertEquals(SaleReportPeriod.CUSTOM, SaleReportPeriod.from(null, true));
    }

    @Test
    @DisplayName("an unrecognised code falls back rather than returning an empty report")
    void unknownCodeFallsBack() {
        assertEquals(SaleReportPeriod.DEFAULT, SaleReportPeriod.from(99, false));
    }

    @Test
    @DisplayName("the old codes still mean what a bookmarked report expects")
    void oldCodesArePreserved() {
        assertEquals(SaleReportPeriod.THIS_MONTH, SaleReportPeriod.from(0, false));
        assertEquals(SaleReportPeriod.CUSTOM, SaleReportPeriod.from(4, true));
    }

    @Test
    @DisplayName("every period starts at midnight and ends at the last instant of its last day")
    void bothBoundsAreInclusive() {
        for (SaleReportPeriod p : SaleReportPeriod.values()) {
            if (p == SaleReportPeriod.CUSTOM) continue;
            LocalDateTime[] r = p.range(MID);
            assertEquals(LocalTime.MIDNIGHT, r[0].toLocalTime(), p + " starts at midnight");
            // MAX, not "hour 23": a bound at 23:00 would pass an hour check and still drop the last hour.
            assertEquals(LocalTime.MAX, r[1].toLocalTime(), p + " includes the whole of its last day");
        }
    }

    @Test
    @DisplayName("the rolling periods end today")
    void rollingPeriodsEndToday() {
        for (SaleReportPeriod p : List.of(SaleReportPeriod.TODAY, SaleReportPeriod.LAST_7_DAYS,
                                          SaleReportPeriod.LAST_30_DAYS)) {
            assertEquals(MID, p.range(MID)[1].toLocalDate(), p + " runs up to today");
        }
    }

    @Test
    @DisplayName("this month is the whole calendar month — code 0 keeps its pre-SR-1 meaning")
    void thisMonthIsWhole() {
        LocalDateTime[] r = SaleReportPeriod.THIS_MONTH.range(MID);
        assertEquals(LocalDate.of(2026, 9, 1), r[0].toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 30), r[1].toLocalDate(),
                "same end bound as AppUtil.lastDateTimeOfMonth, which the dashboard's monthly figures use");
    }

    @Test
    @DisplayName("a single-day period returns that day, not nothing")
    void todayIsOneWholeDay() {
        LocalDateTime[] r = SaleReportPeriod.TODAY.range(MID);
        assertEquals(MID, r[0].toLocalDate());
        assertEquals(MID, r[1].toLocalDate());
        assertTrue(r[1].isAfter(r[0]), "a same-day range that collapsed to a point would return no sales");
    }

    @Test
    @DisplayName("last 7 days is today plus the six before it")
    void lastSevenCountsToday() {
        assertEquals(LocalDate.of(2026, 8, 31), SaleReportPeriod.LAST_7_DAYS.range(MID)[0].toLocalDate());
    }

    @Test
    @DisplayName("last month is the whole previous calendar month")
    void lastMonthIsWhole() {
        LocalDateTime[] r = SaleReportPeriod.LAST_MONTH.range(MID);
        assertEquals(LocalDate.of(2026, 8, 1), r[0].toLocalDate());
        assertEquals(LocalDate.of(2026, 8, 31), r[1].toLocalDate());
    }

    @Test
    @DisplayName("CUSTOM refuses to invent a range — the request owns it")
    void customHasNoComputedRange() {
        assertThrows(IllegalStateException.class, () -> SaleReportPeriod.CUSTOM.range(MID));
    }
}
