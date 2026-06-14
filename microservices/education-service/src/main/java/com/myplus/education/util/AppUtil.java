package com.myplus.education.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Shared helpers for the education flat endpoints, mirroring business-service's AppUtil:
 * date formatting, empty checks, status constants, and short logging helpers.
 */
@Component
public class AppUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppUtil.class);

    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public final String SUCCESS = "SUCCESS";
    public final String FAILED = "FAILED";
    public final String FOUND = "FOUND";
    public final String NOT_FOUND = "NOT_FOUND";
    public final String ERROR = "ERROR";
    public final String INVALID = "INVALID";

    // ---- date helpers ----
    public String todayDateStr() {
        return LocalDateTime.now().format(dateFormatter);
    }

    public String getDateStr(LocalDateTime date) {
        return date == null ? "" : dateFormatter.format(date);
    }

    public String getLocalDateTimeStr(LocalDateTime date) {
        return date == null ? "" : dateTimeFormatter.format(date);
    }

    public String getLocalDateStr(LocalDate date) {
        return date == null ? "" : dateFormatter.format(date);
    }

    public LocalDate getLocalDate(String dateStr) {
        return StringUtils.isEmpty(dateStr) ? LocalDate.now() : LocalDate.parse(dateStr, dateFormatter);
    }

    public LocalDateTime firstDateTimeOfMonth() {
        return LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    public LocalDateTime lastDateTimeOfMonth() {
        int last = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH);
        return LocalDateTime.now().withDayOfMonth(last).withHour(23).withMinute(59).withSecond(59);
    }

    // ---- empty checks ----
    public boolean isEmptyOrNull(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public boolean isEmptyOrNull(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public boolean isEmptyOrNull(Object object) {
        return object == null;
    }

    public boolean isEmptyOrNull(Object[] array) {
        return array == null || array.length == 0;
    }

    public boolean isEmptyOrNull(String string) {
        return string == null || string.equals("null") || string.trim().length() == 0;
    }

    // ---- logging helpers ----
    public void le(Class<?> c, Exception e) {
        LOGGER.error(c.getName() + "  >>>  " + e.getMessage(), e);
    }

    public void li(Class<?> c, String s) {
        LOGGER.info(c.getName() + "  >>>  " + s);
    }

    public void lw(Class<?> c, String s) {
        LOGGER.warn(c.getName() + "  >>>  " + s);
    }
}
