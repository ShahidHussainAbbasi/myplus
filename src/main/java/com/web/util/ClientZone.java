package com.web.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * TZ-2 — the browser's time zone, as main.js reports it in the {@code myplus_tz} cookie. Design:
 * microservices/docs/slices/tz-1-business-time-zone.md §TZ-2.
 *
 * <p>The monolith runs on UTC in its container, so {@code LocalDate.now()} here was the SERVER's day: yesterday from
 * 00:00 to 05:00 in Karachi. {@link #today()} is the person's day, and {@link #forward(HttpHeaders)} hands the same
 * zone to every service (as {@code X-Client-Tz}), where common-security's TenantClock decides "today" from it.
 * Only the zone is trusted, never the browser's clock. No cookie, or an unusable one: Asia/Karachi.
 */
public final class ClientZone {

    public static final String COOKIE = "myplus_tz";
    public static final String HEADER = "X-Client-Tz";
    private static final ZoneId DEFAULT = ZoneId.of("Asia/Karachi");

    private ClientZone() {}

    /** Today in the browser's zone (or the default zone). */
    public static LocalDate today() {
        return LocalDate.now(zone());
    }

    public static ZoneId zone() {
        return browserZone().orElse(DEFAULT);
    }

    /** Adds {@code X-Client-Tz} to an outbound call when the current request came from a browser that sent its zone. */
    public static HttpHeaders forward(HttpHeaders headers) {
        browserZone().ifPresent(z -> headers.set(HEADER, z.getId()));
        return headers;
    }

    static Optional<ZoneId> browserZone() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) return Optional.empty();
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) return parse(c.getValue());
        }
        return Optional.empty();
    }

    /** A real zone id, or nothing — never a guess. */
    static Optional<ZoneId> parse(String value) {
        if (value == null || value.isBlank() || value.length() > 64) return Optional.empty();
        try {
            return Optional.of(ZoneId.of(java.net.URLDecoder.decode(value.trim(), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RuntimeException notAZone) {
            return Optional.empty();
        }
    }
}
