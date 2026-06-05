package com.myplus.agriculture.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AppUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppUtil.class);

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public final String SUCCESS = "SUCCESS";
    public final String FAILURE = "FAILURE";
    public final String FAILED = "FAILED";
    public final String FOUND = "FOUND";
    public final String NOT_FOUND = "NOT_FOUND";
    public final String ERROR = "ERROR";
    public final String INVALID = "INVALID";

    /** Format a LocalDate as dd-MM-yyyy (empty string when null). */
    public String getLocalDateStr(LocalDate date) {
        return date == null ? "" : dateFormatter.format(date);
    }

    /** Parse a dd-MM-yyyy string; falls back to today when empty/null. */
    public LocalDate getLocalDate(String dateStr) {
        if (StringUtils.isEmpty(dateStr)) {
            return LocalDate.now();
        }
        return LocalDate.parse(dateStr, dateFormatter);
    }

    public boolean isEmptyOrNull(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public boolean isEmptyOrNull(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    public boolean isEmptyOrNull(Object object) {
        return object == null;
    }

    public boolean isEmptyOrNull(String string) {
        return string == null || string.equals("null") || string.trim().length() == 0;
    }

    public void le(Class<?> c, Exception e) {
        LOGGER.error(c.getName() + "  >>>  " + e.getMessage(), e);
    }

    public void li(Class<?> c, String s) {
        LOGGER.info(c.getName() + "  >>>  " + s);
    }
}
