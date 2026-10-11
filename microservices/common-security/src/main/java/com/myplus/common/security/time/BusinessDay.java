package com.myplus.common.security.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * H6 C3 — the ACCOUNTING DAY of a recorded instant: the business's day, never the viewer's (client ruling 2026-10-11;
 * the model QuickBooks / Xero / Odoo use — a document never changes date depending on who looks at it).
 *
 * <h3>Why this exists</h3>
 * Instants are stored and held as UTC {@code LocalDateTime}. {@code dated.toLocalDate()} is therefore the UTC
 * calendar day, and anything recorded 00:00–05:00 Karachi landed on the PREVIOUS day — in supplier aging and
 * statements, the payables document date sent to finance, the period-lock check and the invoice QR (27 places,
 * docs/hms-phase1-design.md §4l). Use {@link #of} wherever a recorded instant becomes a DATE.
 *
 * <h3>What it is not</h3>
 * Not for text a person reads on screen (that is the viewer's zone — {@link RenderZone} / the browser), and not for
 * "today" in a form (that is {@link TenantClock#today()}, the person's today — TZ-2).
 */
public final class BusinessDay {

    private BusinessDay() {}

    /** A wall-clock time in the business's zone (a report bound typed as "dd-MM-yyyy HH:mm:ss") as the UTC instant. */
    public static LocalDateTime fromBusinessTime(LocalDateTime local) {
        return local == null ? null
                : local.atZone(TenantClock.businessZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** The business day a UTC instant falls on. 2026-10-10T21:00 UTC → 2026-10-11 in Karachi. */
    public static LocalDate of(LocalDateTime utc) {
        return utc == null ? null
                : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(TenantClock.businessZone()).toLocalDate();
    }

    /** The first UTC instant of a business day — a report's lower bound. 2026-10-11 → 2026-10-10T19:00 in Karachi. */
    public static LocalDateTime start(LocalDate day) {
        return day == null ? null
                : day.atStartOfDay(TenantClock.businessZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * The last UTC instant of a business day — a report's INCLUSIVE upper bound. The end of the day is taken in the
     * business zone and only then converted (converting midnight first and ending the day afterwards ends the range
     * where it starts — a same-day report returned nothing).
     */
    public static LocalDateTime end(LocalDate day) {
        return day == null ? null
                : day.atTime(LocalTime.MAX).atZone(TenantClock.businessZone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
