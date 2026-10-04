package com.myplus.common.security.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * TZ-2 — the ONE answer to "what day is it?" for a business decision. Design:
 * microservices/docs/slices/tz-1-business-time-zone.md §TZ-2.
 *
 * <h3>Why not {@code LocalDate.now()}</h3>
 * It answers in the JVM's zone, and every container runs on UTC. From 00:00 to 05:00 in Karachi it said YESTERDAY, so
 * an expense dated today was refused as "in the future", a payment defaulted to yesterday and a journal landed in the
 * previous day (or a closed period). It is now forbidden at build time (forbiddenapis, service-parent).
 *
 * <h3>Whose day</h3>
 * The person's — the zone their browser reports ({@link #HEADER}, set by the monolith from the {@code myplus_tz}
 * cookie main.js writes, passed by the gateway, copied service-to-service by GatewayIdentityForwarding). This is
 * Odoo's {@code context_today}: the CLIENT's zone, never the client's CLOCK, so the most it can move "today" is the real
 * ±14 h world range. With no request (relay, scheduler) or an unusable zone: {@code app.tz.default-zone}.
 */
public final class TenantClock {

    /** The request header carrying the browser's IANA zone id, e.g. {@code Asia/Karachi}. */
    public static final String HEADER = "X-Client-Tz";

    /** The zone used when no person is asking (background work) — config-server, default Asia/Karachi. */
    public static final String DEFAULT_ZONE_PROPERTY = "app.tz.default-zone";

    private static volatile ZoneId defaultZone = ZoneId.of("Asia/Karachi");
    private static volatile Clock clock = Clock.systemUTC();

    private TenantClock() {}

    /** Today, in the zone of the person this request is for (or the default zone). */
    public static LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    /** This month, on the same terms as {@link #today()}. */
    public static YearMonth thisMonth() {
        return YearMonth.from(today());
    }

    /** The zone "today" is decided in. */
    public static ZoneId zone() {
        return clientZone().orElse(defaultZone);
    }

    /** The browser's zone for the request on this thread, if it sent a usable one. */
    public static Optional<ZoneId> clientZone() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return RenderZone.parse(request.getHeader(HEADER));
        }
        return Optional.empty();
    }

    /** Set once at startup from {@link #DEFAULT_ZONE_PROPERTY}; an unusable value keeps the current default. */
    public static void configureDefaultZone(String zoneId) {
        RenderZone.parse(zoneId).ifPresent(z -> defaultZone = z);
    }

    /** Tests only: fix the instant "now" is read from. Pass {@code null} to return to the system clock. */
    public static void useClock(Clock fixed) {
        clock = fixed == null ? Clock.systemUTC() : fixed;
    }
}
