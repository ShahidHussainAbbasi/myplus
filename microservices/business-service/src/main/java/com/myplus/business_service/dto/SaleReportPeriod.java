package com.myplus.business_service.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.function.Function;

/**
 * The Sale Detail Report's period vocabulary (slice SR-1).
 *
 * <p>Each constant carries its own date range, so adding a period is one enum constant rather than another
 * branch in the controller. Pure — no Spring, no database, and {@code today} is a parameter rather than a
 * call to {@code now()} — so the ranges are unit tested directly. This mirrors {@link SaleReportGrouping},
 * which is the same shape for the same reason.
 *
 * <h3>Why the default moved</h3>
 * The report used to open on the CURRENT MONTH. Measured on the 6th of a month against a shop with 541 sale
 * lines, that showed <b>6 of them</b>; on the 1st it would have shown a trading business an empty screen. A
 * rolling <b>last 30 days</b> always answers "what have I been selling", which is the question the screen is
 * opened to ask.
 *
 * <h3>Why the old codes keep their meanings</h3>
 * {@code 0} still means this month and {@code 4} still means a custom range, so a bookmarked report or a
 * saved export link keeps working. Only the DEFAULT changed. The codes {@code 1/2/3/5} were unused —
 * {@code rp} is read in exactly one place in the product ({@code SellController.loadSR}) and offered by
 * exactly one control, so extending the vocabulary cannot disturb anything else.
 */
public enum SaleReportPeriod {

    /** This calendar month, 1st to last day. The previous default; kept so old links still mean it. */
    THIS_MONTH(0, today -> new LocalDate[] { today.withDayOfMonth(1),
            today.withDayOfMonth(today.lengthOfMonth()) }),

    /** Today only — the shift a cashier is standing in. */
    TODAY(1, today -> new LocalDate[] { today, today }),

    /** The last 7 days INCLUDING today, i.e. today and the six before it. */
    LAST_7_DAYS(2, today -> new LocalDate[] { today.minusDays(6), today }),

    /** ⭐ The default. The last 30 days including today. */
    LAST_30_DAYS(3, today -> new LocalDate[] { today.minusDays(29), today }),

    /**
     * The dates the operator typed. Its range is resolved by the caller from {@code sd}/{@code ed}, not
     * here — this constant exists so the controller can name the case rather than test for it.
     */
    CUSTOM(4, null),

    /** The previous calendar month, whole — what a shop compares this month against. */
    LAST_MONTH(5, today -> {
        LocalDate first = today.minusMonths(1).withDayOfMonth(1);
        return new LocalDate[] { first, first.withDayOfMonth(first.lengthOfMonth()) };
    });

    /** The period every request gets when it does not ask for one. */
    public static final SaleReportPeriod DEFAULT = LAST_30_DAYS;

    private final int code;
    private final Function<LocalDate, LocalDate[]> daysOf;

    SaleReportPeriod(int code, Function<LocalDate, LocalDate[]> daysOf) {
        this.code = code;
        this.daysOf = daysOf;
    }

    public int getCode() {
        return code;
    }

    /**
     * Resolve a request's {@code rp}.
     *
     * @param code      the requested period, or {@code null} when the client sent none
     * @param hasDates  whether the request carried {@code sd} or {@code ed}
     * @return never {@code null} — an absent or unrecognised code means {@link #DEFAULT}, because a report
     *         that answers a malformed question with an empty result teaches an operator their data is gone
     */
    public static SaleReportPeriod from(Integer code, boolean hasDates) {
        if (code == null) {
            // Dates with no period is what a deep link or a pre-filled rail looks like: honour them.
            return hasDates ? CUSTOM : DEFAULT;
        }
        for (SaleReportPeriod p : values()) {
            if (p.code == code.intValue()) return p;
        }
        return DEFAULT;
    }

    /**
     * This period's bounds, both INCLUSIVE: start-of-day to end-of-day, so a single-day period returns that
     * day's sales rather than nothing. (The same inclusivity {@code AppUtil.endOfDay} exists to guarantee for
     * a custom range — see the note there about picking one day for both ends.)
     *
     * @throws IllegalStateException for {@link #CUSTOM}, whose range comes from the request, not from here
     */
    public LocalDateTime[] range(LocalDate today) {
        if (daysOf == null) {
            throw new IllegalStateException("CUSTOM has no computed range — resolve it from sd/ed");
        }
        LocalDate[] days = daysOf.apply(today);
        return new LocalDateTime[] { days[0].atStartOfDay(), days[1].atTime(LocalTime.MAX) };
    }
}
