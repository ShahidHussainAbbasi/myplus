package com.myplus.business_service.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.myplus.common.security.time.RenderZone;
import com.myplus.common.security.time.RenderZoneFilter;

/**
 * H6 C2/C3 — a sales report's day bounds, through the real filter (X-Render-Tz): the BUSINESS's day (C3), whatever
 * the viewer's zone.
 *
 * The defect this guards: endOfDay(getDateTime(ed)) converted the typed midnight FIRST (11-10 00:00 Karachi →
 * 10-10 19:00 UTC), which is not midnight, so endOfDay left it alone and the range ended where it started — a
 * same-day report returned nothing. getEndOfDayUtc ends the day locally, then converts.
 */
class ReportDayBoundsTest {

    private final AppUtil appUtil = new AppUtil();

    @org.junit.jupiter.api.BeforeEach
    void businessZoneIsKarachi() { com.myplus.common.security.time.TenantClock.configureDefaultZone("Asia/Karachi"); }

    private LocalDateTime[] boundsIn(String zone, String sd, String ed) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (zone != null) req.addHeader(RenderZone.HEADER, zone);
        AtomicReference<LocalDateTime[]> out = new AtomicReference<>();
        new RenderZoneFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            try {
                out.set(new LocalDateTime[] { appUtil.getDateTime(sd), appUtil.getEndOfDayUtc(ed) });
            } catch (java.text.ParseException e) {
                throw new IllegalStateException(e);
            }
        });
        return out.get();
    }

    @Test
    void a_same_day_report_in_karachi_covers_the_whole_karachi_day() throws Exception {
        LocalDateTime[] b = boundsIn("Asia/Karachi", "11-10-2026 00:00:00", "11-10-2026 00:00:00");
        assertThat(b[0]).isEqualTo(LocalDateTime.of(2026, 10, 10, 19, 0));
        assertThat(b[1]).isEqualTo(LocalDateTime.of(2026, 10, 11, 18, 59, 59, 999_999_999));
        // a sale at 02:00 Karachi (21:00 UTC on the 10th) is in the report for the 11th
        LocalDateTime sale = LocalDateTime.of(2026, 10, 10, 21, 0);
        assertThat(sale).isAfterOrEqualTo(b[0]).isBeforeOrEqualTo(b[1]);
    }

    @Test
    void the_old_order_would_have_returned_nothing() throws Exception {
        LocalDateTime[] b = boundsIn("Asia/Karachi", "11-10-2026 00:00:00", "11-10-2026 00:00:00");
        LocalDateTime oldEnd = appUtil.endOfDay(b[0]);   // what endOfDay(getDateTime(ed)) produced
        assertThat(oldEnd).isEqualTo(b[0]);              // start == end: an empty report
        assertThat(b[1]).isAfter(oldEnd);
    }

    @Test
    void the_bounds_are_the_businesss_day_whoever_looks_and_with_no_zone_at_all() throws Exception {
        // H6 C3 (client ruling 2026-10-11): a transaction belongs to the BUSINESS's day, so a report's days do too —
        // a viewer abroad, or a service-to-service call with no zone, gets the same bounds as a viewer in Karachi
        LocalDateTime[] karachi = boundsIn("Asia/Karachi", "11-10-2026 00:00:00", "11-10-2026 00:00:00");
        LocalDateTime[] abroad = boundsIn("Pacific/Kiritimati", "11-10-2026 00:00:00", "11-10-2026 00:00:00");
        LocalDateTime[] none = boundsIn(null, "11-10-2026 00:00:00", "11-10-2026 00:00:00");
        assertThat(abroad).containsExactly(karachi);
        assertThat(none).containsExactly(karachi);
    }
}
