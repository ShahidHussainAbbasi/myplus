package com.myplus.business_service.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.myplus.common.security.time.RenderZone;

/**
 * MS-5 — dates typed on a screen, into the entity: the same rules as AppUtil's "IgnoreEmptyOrNull" ModelMapper
 * converters. Blank → null (never today); {@code dd-MM-yyyy HH:mm:ss}, or a date alone at the start of its day, in the
 * render zone. MapStructOracleTest holds this to identical output.
 */
public interface InputDates {

    DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    default LocalDate inputDate(String v) {
        return v == null || v.isBlank() ? null : LocalDate.parse(v, DATE);
    }

    default LocalDateTime inputDateTime(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return RenderZone.fromDisplay(LocalDateTime.parse(v, DATE_TIME));
        } catch (DateTimeParseException dateOnly) {
            return RenderZone.fromDisplay(LocalDate.parse(v, DATE).atStartOfDay());
        }
    }
}
