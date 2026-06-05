package com.myplus.welfare.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AppUtil {

    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public String todayDateStr() {
        return LocalDateTime.now().format(dateFormatter);
    }

    public String getDateStr(LocalDateTime date) {
        return date == null ? "" : dateFormatter.format(date);
    }

    public String getLocalDateTimeStr(LocalDateTime date) {
        return date == null ? "" : dateTimeFormatter.format(date);
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
}
