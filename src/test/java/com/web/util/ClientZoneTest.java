package com.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * TZ-2 — the monolith hands the browser's zone on, and no browser script computes "today" in UTC.
 * Design: microservices/docs/slices/tz-1-business-time-zone.md §TZ-2.
 */
class ClientZoneTest {

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void browser(String cookieValue) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (cookieValue != null) r.setCookies(new Cookie(ClientZone.COOKIE, cookieValue));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    @Test
    @DisplayName("the cookie main.js writes (URL-encoded) becomes X-Client-Tz on every outbound call")
    void forwardsTheBrowserZone() {
        browser("Asia%2FKarachi");
        assertEquals(ZoneId.of("Asia/Karachi"), ClientZone.zone());
        assertEquals("Asia/Karachi", ClientZone.forward(new HttpHeaders()).getFirst(ClientZone.HEADER));
        browser("America/New_York");
        assertEquals("America/New_York", ClientZone.forward(new HttpHeaders()).getFirst(ClientZone.HEADER));
    }

    @Test
    @DisplayName("no cookie, a bad one, or no request: nothing forwarded, Asia/Karachi locally — never a guess")
    void ignoresWhatIsNotAZone() {
        for (String bad : new String[] { null, "", "Mars%2FOlympus", "<script>", "x".repeat(80) }) {
            browser(bad);
            assertNull(ClientZone.forward(new HttpHeaders()).getFirst(ClientZone.HEADER), "forwarded " + bad);
            assertEquals(ZoneId.of("Asia/Karachi"), ClientZone.zone());
        }
        RequestContextHolder.resetRequestAttributes();
        assertNull(ClientZone.forward(new HttpHeaders()).getFirst(ClientZone.HEADER));
    }

    /** {@code x.toISOString().slice(0, 10)} and its cousins: the UTC day, i.e. YESTERDAY before 05:00 in Karachi. */
    private static final Pattern UTC_DAY = Pattern.compile("toISOString\\(\\)\\s*\\.\\s*(slice|substr|substring|split)\\b");

    @Test
    @DisplayName("⭐ no app script builds a date from toISOString() — use dateToYMD() (main.js)")
    void noUtcDatesInBrowserScripts() throws IOException {
        Path js = Paths.get("src/main/resources/static/js");
        assertTrue(Files.isDirectory(js), "run from the repo root");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(js)) {
            offenders = files.filter(p -> p.toString().endsWith(".js"))
                    .filter(p -> !p.toString().replace('\\', '/').matches(".*/(lib|vendor|plugins)/.*"))
                    .filter(p -> !p.getFileName().toString().matches("(moment|date|docx).*\\.js|.*\\.min\\.js"))
                    .flatMap(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> UTC_DAY.matcher(lines.get(i)).find())
                                    .mapToObj(i -> js.relativize(p) + ":" + (i + 1));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.toList());
        }
        assertEquals(List.of(), offenders, "UTC dates in browser scripts");
    }
}
