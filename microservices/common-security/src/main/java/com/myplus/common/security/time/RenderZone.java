package com.myplus.common.security.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * TZ-1 — the zone a browser-bound HTTP response is RENDERED in. Design:
 * microservices/docs/slices/tz-1-business-time-zone.md.
 *
 * <h3>The model</h3>
 * Every service runs on UTC ({@link UtcDefaultTimeZone} pins the JVM), so a {@code LocalDateTime} in memory is a UTC
 * wall-clock time. People see their business's local time. The conversion happens at ONE place: the web layer's
 * JSON converter ({@link RenderZoneWebConfig}), for requests the monolith marks with {@code X-Render-Tz}.
 *
 * <h3>Why a request-scoped holder, set only by {@link RenderZoneFilter}</h3>
 * It is the ONLY signal that the thread is serving a person rather than another service. It is never forwarded
 * (GatewayIdentityForwarding does not copy it), so a service-to-service call made while serving a browser request
 * still speaks UTC — the callee's request has no zone. And only the web layer's own converter reads it, so an
 * outbox payload, a cache entry or a RestTemplate body serialised on the same thread stays UTC.
 */
public final class RenderZone {

    /** The request header the monolith (the only browser-facing door) sets on every call it proxies. */
    public static final String HEADER = "X-Render-Tz";

    private static final ThreadLocal<ZoneId> ZONE = new ThreadLocal<>();

    private RenderZone() {}

    public static Optional<ZoneId> current() { return Optional.ofNullable(ZONE.get()); }

    static void set(ZoneId zone) { ZONE.set(zone); }

    static void clear() { ZONE.remove(); }

    /** A UTC wall-clock time as the reader should see it — unchanged when nobody is being rendered for. */
    public static LocalDateTime toDisplay(LocalDateTime utc) {
        ZoneId z = ZONE.get();
        if (utc == null || z == null) return utc;
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(z).toLocalDateTime();
    }

    /** A time a person typed in their business's zone, as the UTC wall-clock the services work in. */
    public static LocalDateTime fromDisplay(LocalDateTime local) {
        ZoneId z = ZONE.get();
        if (local == null || z == null) return local;
        return local.atZone(z).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Parse a header value; anything that is not a real zone id is ignored rather than guessed at. */
    static Optional<ZoneId> parse(String header) {
        if (header == null || header.isBlank()) return Optional.empty();
        try {
            return Optional.of(ZoneId.of(header.trim()));
        } catch (RuntimeException notAZone) {
            return Optional.empty();
        }
    }
}
