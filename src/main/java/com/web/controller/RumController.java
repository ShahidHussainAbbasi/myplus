package com.web.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives real-user timing beacons from {@code /js/common/rum.js}.
 *
 * <h3>Why this is a log line and not a table</h3>
 * The first question is "are real shops slower than the developer machine, and where" — which is answered by
 * grepping a few days of lines, not by a schema. A table invites dashboards, retention policy and a migration
 * before anyone has learned whether the data is even useful. If it proves useful, promoting it to
 * audit-service or a metrics backend is a deliberate second step with its own design.
 *
 * <p>Logged as one structured line per beacon so it can be grepped, shipped to the existing OTel/Grafana
 * pipeline, or piped into anything that reads stdout.
 *
 * <h3>Deliberately unauthenticated-tolerant, and deliberately silent</h3>
 * The beacon fires as the page is going away, when the session may already be gone. It answers
 * {@code 204 No Content} always: {@code sendBeacon} discards the response, so any body is wasted bytes, and a
 * failure here must never surface to an operator who has simply closed a tab.
 *
 * <h3>What it must never accept</h3>
 * The payload carries a tenant id, timings and Core Web Vitals — no user, no customer, no product. That is
 * enforced at the source, and the size cap below is the backstop: a monitoring endpoint is an unauthenticated
 * write surface, so it takes a small fixed shape or nothing.
 */
@RestController
public class RumController {

    private static final Logger RUM = LoggerFactory.getLogger("RUM");

    /**
     * A beacon is a few hundred bytes. Anything materially larger is either a bug or someone probing an open
     * write endpoint, and neither deserves a log line the size of a page.
     */
    private static final int MAX_CHARS = 4000;

    @PostMapping("/rum")
    public ResponseEntity<Void> collect(@RequestBody(required = false) Map<String, Object> beacon) {
        try {
            if (beacon != null) {
                String line = String.valueOf(beacon);
                if (line.length() > MAX_CHARS) line = line.substring(0, MAX_CHARS) + "…(truncated)";
                RUM.info("{}", line);
            }
        } catch (Exception ignored) {
            // Monitoring must never generate incidents of its own. A malformed beacon is discarded.
        }
        // 204 regardless — sendBeacon ignores the response, and a page being closed cannot act on an error.
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
