package com.myplus.marketplace.multiseller.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * T+N = N <b>business</b> days after the settlement trigger date T (source §15). Delivered Friday, T+1 is Monday,
 * or later if Monday is a bank holiday.
 *
 * <p>The weekend is a parameter, not an assumption: the Saturday/Sunday default matches Pakistani banks, but a
 * Friday/Saturday market differs, and the holiday list is per country and year.
 */
public final class BusinessDayCalendar {

    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    public BusinessDayCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        this.weekend = weekend.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekend);
        this.holidays = Set.copyOf(holidays);
        if (this.weekend.size() >= 7) throw new IllegalArgumentException("a week needs at least one business day");
    }

    public static BusinessDayCalendar saturdaySundayWeekend(Set<LocalDate> holidays) {
        return new BusinessDayCalendar(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), holidays);
    }

    public boolean isBusinessDay(LocalDate d) {
        return !weekend.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    public LocalDate plusBusinessDays(LocalDate t, int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        LocalDate d = t;
        for (int added = 0; added < n; ) {
            d = d.plusDays(1);
            if (isBusinessDay(d)) added++;
        }
        return d;
    }
}
