package com.myplus.common.security;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter puts tenant identity into MDC for the duration of the request and clears it afterwards.
 * Span/baggage calls are exercised here too (they no-op without an OTel SDK, which is exactly the
 * uninstrumented-service safety property we rely on).
 */
class TenantTelemetryFilterTest {

    @Test
    void stampsTenantMdcDuringRequestAndClearsAfter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Org-Id", "42");
        req.addHeader("X-User-Id", "7");

        String[] seenDuringRequest = new String[2];
        new TenantTelemetryFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            seenDuringRequest[0] = MDC.get("org_id");
            seenDuringRequest[1] = MDC.get("user_id");
        });

        assertThat(seenDuringRequest[0]).isEqualTo("42");
        assertThat(seenDuringRequest[1]).isEqualTo("7");
        // Must not leak onto the pooled request thread.
        assertThat(MDC.get("org_id")).isNull();
        assertThat(MDC.get("user_id")).isNull();
    }

    @Test
    void passesThroughUntouchedWhenNoIdentityHeaders() throws Exception {
        boolean[] chainCalled = {false};
        new TenantTelemetryFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> chainCalled[0] = true);

        assertThat(chainCalled[0]).isTrue();
        assertThat(MDC.get("org_id")).isNull();
    }

    @Test
    void treatsBlankAndLiteralNullAsAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Org-Id", "null");
        req.addHeader("X-User-Id", "  ");

        String[] seen = new String[2];
        new TenantTelemetryFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            seen[0] = MDC.get("org_id");
            seen[1] = MDC.get("user_id");
        });

        assertThat(seen[0]).isNull();
        assertThat(seen[1]).isNull();
    }
}
