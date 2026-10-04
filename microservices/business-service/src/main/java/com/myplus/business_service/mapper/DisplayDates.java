package com.myplus.business_service.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.myplus.common.security.time.RenderZone;

/**
 * MS-3 — the screen date formats, once, for every MapStruct mapper that shows dates. The same patterns and render-zone
 * step AppUtil's ModelMapper converters use ({@code dd-MM-yyyy}, {@code dd-MM-yyyy HH:mm:ss}); MapStructOracleTest holds
 * the two to identical output, except for MS-F2 below.
 */
public interface DisplayDates {

    DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    /*
     * MS-F2 (user decision 2026-10-04): no time recorded shows BLANK. The old ModelMapper converters answered "now" for a
     * null. Preventive: on the 4 Oct data no row these screens list had a null (the 61 null-`updated` purchases are
     * opening bills, which the purchase list skips), but any future one would have shown the current time.
     */
    default String displayDate(LocalDate v) {
        return v == null ? null : DATE.format(v);
    }

    default String displayDateTime(LocalDateTime v) {
        return v == null ? null : DATE_TIME.format(RenderZone.toDisplay(v));
    }
}
