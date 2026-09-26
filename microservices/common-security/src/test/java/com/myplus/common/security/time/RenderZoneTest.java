package com.myplus.common.security.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TZ-1 — the render zone converts browser-bound LocalDateTimes, and NOTHING else.
 *
 * <p>INV-000054 (org 13) was rung at 16:53 Pakistan time; the service holds 11:53 (UTC) and the receipt printed it.
 */
class RenderZoneTest {

    private static final ZoneId KARACHI = ZoneId.of("Asia/Karachi");
    private static final LocalDateTime SALE_UTC = LocalDateTime.of(2026, 9, 24, 11, 53, 10);

    /** The application mapper as Spring Boot builds it: java.time on, dates as ISO strings. */
    private static ObjectMapper appMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static ObjectMapper webMapper() {
        return appMapper().registerModule(RenderZoneWebConfig.module());
    }

    @AfterEach
    void clear() { RenderZone.clear(); }

    @Test
    @DisplayName("⭐ INV-000054: 11:53 UTC renders as 16:53 for a Pakistan business")
    void rendersInTheBusinessZone() throws Exception {
        RenderZone.set(KARACHI);
        assertThat(webMapper().writeValueAsString(Map.of("dated", SALE_UTC)))
                .isEqualTo("{\"dated\":\"2026-09-24T16:53:10\"}");
    }

    @Test
    @DisplayName("the same instant renders in a US business's own zone")
    void rendersInAnotherBusinessZone() throws Exception {
        RenderZone.set(ZoneId.of("America/New_York"));   // EDT, UTC−4 on this date
        assertThat(webMapper().writeValueAsString(Map.of("dated", SALE_UTC)))
                .isEqualTo("{\"dated\":\"2026-09-24T07:53:10\"}");
    }

    @Test
    @DisplayName("no render zone (a service-to-service call) → UTC, byte-identical to before")
    void noZoneIsUtc() throws Exception {
        assertThat(webMapper().writeValueAsString(Map.of("dated", SALE_UTC)))
                .isEqualTo("{\"dated\":\"2026-09-24T11:53:10\"}");
    }

    @Test
    @DisplayName("a typed-in local time comes back as UTC — an exact round trip")
    void inboundIsConvertedBack() throws Exception {
        RenderZone.set(KARACHI);
        Holder h = webMapper().readValue("{\"at\":\"2026-09-24T16:53:10\"}", Holder.class);
        assertThat(h.at).isEqualTo(SALE_UTC);
    }

    @Test
    @DisplayName("a LocalDate (a due date, a birthday) is a calendar day — never shifted")
    void datesAreNeverShifted() throws Exception {
        RenderZone.set(KARACHI);
        assertThat(webMapper().writeValueAsString(Map.of("due", LocalDate.of(2026, 9, 30))))
                .isEqualTo("{\"due\":\"2026-09-30\"}");
    }

    @Test
    @DisplayName("⭐ the APPLICATION mapper never converts, even mid-request — outbox, cache and service calls stay UTC")
    void appMapperIsUntouched() throws Exception {
        ObjectMapper shared = appMapper();
        List<HttpMessageConverter<?>> mvc = new ArrayList<>(List.of(new MappingJackson2HttpMessageConverter(shared)));
        new RenderZoneWebConfig().extendMessageConverters(mvc);

        RenderZone.set(KARACHI);
        assertThat(shared.writeValueAsString(Map.of("dated", SALE_UTC)))
                .as("the shared mapper").isEqualTo("{\"dated\":\"2026-09-24T11:53:10\"}");
        ObjectMapper web = ((MappingJackson2HttpMessageConverter) mvc.get(0)).getObjectMapper();
        assertThat(web).as("the web layer got its own mapper").isNotSameAs(shared);
        assertThat(web.writeValueAsString(Map.of("dated", SALE_UTC))).isEqualTo("{\"dated\":\"2026-09-24T16:53:10\"}");
    }

    @Test
    @DisplayName("the filter sets the zone for ONE request and always clears it; a bad header is ignored")
    void filterScopesAndClears() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RenderZone.HEADER, "Asia/Karachi");
        ZoneId[] seen = new ZoneId[1];
        new RenderZoneFilter().doFilter(req, new MockHttpServletResponse(),
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse s) {
                        seen[0] = RenderZone.current().orElse(null);
                    }
                });
        assertThat(seen[0]).isEqualTo(KARACHI);
        assertThat(RenderZone.current()).as("cleared for the next request on this thread").isEmpty();

        assertThat(RenderZone.parse("Not/AZone")).isEmpty();
        assertThat(RenderZone.parse("")).isEmpty();
    }

    static class Holder { public LocalDateTime at; }
}
