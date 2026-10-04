package com.myplus.common.security.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * TZ-2 — "today" is the CLIENT's day. The defect: 02:38 in Karachi on 5 Oct is 21:38 UTC on 4 Oct, so a UTC
 * {@code LocalDate.now()} called the shop's today "the future".
 */
class TenantClockTest {

    /** 02:38 PKT on 5 October = 21:38 UTC on 4 October — the hour the expense was refused. */
    private static final Instant NIGHT = Instant.parse("2026-10-04T21:38:00Z");

    @AfterEach
    void reset() {
        TenantClock.useClock(null);
        TenantClock.configureDefaultZone("Asia/Karachi");
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String zone) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (zone != null) r.addHeader(TenantClock.HEADER, zone);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    @Test
    @DisplayName("the browser's zone decides the day — not the UTC server clock")
    void clientZoneDecides() {
        TenantClock.useClock(Clock.fixed(NIGHT, ZoneOffset.UTC));
        request("Asia/Karachi");
        assertThat(TenantClock.today()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(LocalDate.now(Clock.fixed(NIGHT, ZoneOffset.UTC))).as("what the server used to say")
                .isEqualTo(LocalDate.of(2026, 10, 4));
        request("America/New_York");
        assertThat(TenantClock.today()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("no request (relay, scheduler) → the configured default zone")
    void backgroundUsesDefault() {
        TenantClock.useClock(Clock.fixed(NIGHT, ZoneOffset.UTC));
        assertThat(TenantClock.zone()).isEqualTo(ZoneId.of("Asia/Karachi"));
        assertThat(TenantClock.today()).isEqualTo(LocalDate.of(2026, 10, 5));
        TenantClock.configureDefaultZone("UTC");
        assertThat(TenantClock.today()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("a missing or unusable zone is ignored, never guessed at")
    void badZoneFallsBack() {
        TenantClock.useClock(Clock.fixed(NIGHT, ZoneOffset.UTC));
        for (String bad : new String[] { null, "", "  ", "Mars/Olympus", "<script>" }) {
            request(bad);
            assertThat(TenantClock.zone()).as("zone for %s", bad).isEqualTo(ZoneId.of("Asia/Karachi"));
        }
        TenantClock.configureDefaultZone("Not/AZone");
        assertThat(TenantClock.zone()).as("a bad default keeps the old one").isEqualTo(ZoneId.of("Asia/Karachi"));
    }

    @Test
    @DisplayName("this month follows the same day — 1 Nov in Karachi is still October in UTC")
    void thisMonth() {
        TenantClock.useClock(Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneOffset.UTC));
        request("Asia/Karachi");
        assertThat(TenantClock.thisMonth()).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    @DisplayName("the zone travels service-to-service with the identity headers")
    void forwardedOnHops() throws Exception {
        request("Asia/Karachi");
        MockClientHttpRequest out = new MockClientHttpRequest(HttpMethod.GET, java.net.URI.create("http://callee/x"));
        AtomicReference<String> seen = new AtomicReference<>();
        GatewayIdentityForwarding.interceptor().intercept(out, new byte[0], (req, body) -> {
            seen.set(req.getHeaders().getFirst(TenantClock.HEADER));
            return (ClientHttpResponse) null;
        });
        assertThat(seen.get()).isEqualTo("Asia/Karachi");
    }
}
