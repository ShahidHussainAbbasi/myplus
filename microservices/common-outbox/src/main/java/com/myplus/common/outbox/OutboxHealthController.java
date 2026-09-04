package com.myplus.common.outbox;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * D-6 — the operator's view of what has not been delivered, and the control that sends it again.
 *
 * <h3>⚠ The base path is a PROPERTY, because the gateway does not treat every service the same</h3>
 * Verified against {@code api-gateway/application.yml} rather than assumed, and the two halves disagree:
 *
 * <pre>
 *   business-service   Path=/api/business/**    StripPrefix=2   → the controller sees  /outbox-health
 *   education-service  Path=/api/education/**   StripPrefix=2   → the controller sees  /outbox-health
 *   catalog-service    Path=/api/catalog/**     no strip        → it sees  /api/catalog/outbox-health
 *   auth-service       Path=/api/auth/**        no strip        → it sees  /api/auth/outbox-health
 * </pre>
 *
 * So no single hard-coded mapping can serve all four, and a wrong one produces a 404 that reads exactly like
 * an unregistered bean. The default here is empty — correct for the stripped services — and the two that keep
 * their prefix set {@code outbox.health.base-path} in their own config.
 *
 * <h3>ROLE_ADMIN, never ADMIN_PRIVILEGE</h3>
 * Every tenant owner holds the super privilege set inside their own organization, so a privilege gate would
 * hand every customer the platform's delivery state and a button that replays events into books. The same
 * reasoning E1, E2, E4 and E5 all record.
 *
 * <h3>Refusals are the shared envelope</h3>
 * {@link IllegalArgumentException} — unknown table, no scope, no reason — reaches the caller as the
 * {@code success:false} envelope each service's own handler produces, with the sentence written for the
 * person reading it on the console.
 */
@RestController
@RequestMapping("${outbox.health.base-path:}")
public class OutboxHealthController {

    private final OutboxHealthService health;
    private final ObjectProvider<OutboxRedriveAudit> audit;
    private final Environment environment;

    /** Dev-only fixture seeding, defaulting ON for development exactly as {@code SetupDataLoader} does. */
    private final boolean seedFixtures;

    public OutboxHealthController(OutboxHealthService health, ObjectProvider<OutboxRedriveAudit> audit,
                                  Environment environment) {
        this.health = health;
        this.audit = audit;
        this.environment = environment;
        this.seedFixtures = Boolean.parseBoolean(
                environment.getProperty("app.seed-test-fixtures", "true"));
    }

    /** What is waiting, what has been given up on, and why. */
    @GetMapping("/outbox-health")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, Object> health() {
        return Map.of("success", true, "data", health.health());
    }

    /**
     * Put failed rows back on the queue.
     *
     * <p>Body: {@code table, ids?|all, reason}. A reason is required by THIS endpoint, not merely by the
     * console — the endpoint is reachable without the screen, and re-driving events into a ledger unexplained
     * is exactly the thing somebody needs to be able to ask about six months later (E2's rule).
     */
    @PostMapping("/outbox-health/redrive")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, Object> redrive(@RequestBody Map<String, Object> body) {
        String table = str(body.get("table"));
        String reason = str(body.get("reason"));
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required to re-send undelivered records.");

        int reset = health.redrive(table, ids(body.get("ids")), Boolean.TRUE.equals(body.get("all")));

        /*
         * Recorded AFTER the reset, and never allowed to undo it: a re-drive that succeeded and then failed to
         * be recorded is still a re-drive, and rolling it back would leave the rows dead-lettered for a reason
         * nobody asked for. The audit producer's own outbox makes the record recoverable.
         */
        OutboxRedriveAudit a = audit.getIfAvailable();
        if (a != null) {
            try {
                a.redriven(table, reset, reason);
            } catch (RuntimeException ignored) {
                // The producer logs it; the re-drive stands.
            }
        }
        return Map.of("success", true, "data", Map.of("table", table, "reset", reset));
    }

    /**
     * Seed one genuinely-failed row, so a gate can prove the round trip without touching real lost events.
     *
     * <h3>⚠ Two independent guards, and the second is the one that matters</h3>
     * {@code app.seed-test-fixtures} defaults on for development — but the {@code prod} profile is checked
     * SEPARATELY, so setting that property in production still refuses. A single flag is one careless
     * environment variable away from an endpoint that writes fixture rows into a customer's system.
     */
    @PostMapping("/outbox-health/seed-failed")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, Object> seedFailed(@RequestBody Map<String, Object> body) {
        if (!seedFixtures || isProd())
            throw new IllegalArgumentException("Fixture seeding is not available on this deployment.");
        long id = health.seedFailed(str(body.get("table")), str(body.get("reason")));
        return Map.of("success", true, "data", Map.of("id", id));
    }

    private boolean isProd() {
        for (String p : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Row ids, or null. A malformed id is REFUSED rather than skipped — a re-drive must hit what was named. */
    private static List<Long> ids(Object o) {
        if (!(o instanceof List<?> raw) || raw.isEmpty()) return null;
        return raw.stream().map(v -> {
            if (v instanceof Number n) return n.longValue();
            try {
                return Long.valueOf(String.valueOf(v).trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("Not a row id: " + v);
            }
        }).toList();
    }
}
