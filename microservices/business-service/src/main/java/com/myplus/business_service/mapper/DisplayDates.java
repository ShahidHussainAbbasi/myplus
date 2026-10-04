package com.myplus.business_service.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.myplus.common.security.time.RenderZone;

/**
 * MS-3 — the screen date formats, once, for every MapStruct mapper that shows dates. The same patterns and render-zone
 * step AppUtil's ModelMapper converters use ({@code dd-MM-yyyy}, {@code dd-MM-yyyy HH:mm:ss}); MapStructOracleTest holds
 * the two to identical output, null included.
 */
public interface DisplayDates {

    DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    /*
     * ⚠ NULL → TODAY / NOW, deliberately preserved (found by the MS-3 oracle's empty-source run). ModelMapper invoked
     * AppUtil's converters for null sources too, and those answer "today" / "now" — so a record with no `updated` shows
     * the current time. That fabricates a timestamp and is recorded for a DECISION (ms-1-mapstruct-migration.md §6,
     * MS-F2); it is reproduced here, explicitly, so the migration itself changes nothing a screen sees.
     */
    default String displayDate(LocalDate v) {
        return DATE.format(v == null ? com.myplus.common.security.time.TenantClock.today() : v);
    }

    default String displayDateTime(LocalDateTime v) {
        return DATE_TIME.format(RenderZone.toDisplay(v == null ? LocalDateTime.now() : v));
    }
}
