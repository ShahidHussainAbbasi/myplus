package com.myplus.common.security;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps the current tenant/user identity onto telemetry so logs and traces are filterable
 * <b>per tenant</b> — the key payoff of observability on a multi-tenant SaaS. For each request it
 * takes the gateway-stamped {@code X-Org-Id} / {@code X-User-Id} and writes them to:
 * <ul>
 *   <li><b>logback MDC</b> ({@code org_id}, {@code user_id}) — captured into exported OTLP log
 *       records (become Loki structured metadata you can filter on);</li>
 *   <li>the current <b>server span</b>'s attributes — so Tempo trace search can filter by tenant;</li>
 *   <li>OpenTelemetry <b>baggage</b> — propagates to downstream service calls (and their spans/logs).</li>
 * </ul>
 *
 * <p>Reads the headers directly rather than the {@code SecurityContext}, so it is independent of
 * filter ordering and also covers internal service-to-service calls that forward {@code X-Org-Id}
 * (see {@link GatewayIdentityForwarding}). All OpenTelemetry API calls are safe no-ops when no SDK
 * is on the classpath, so this filter is harmless on an uninstrumented service.
 *
 * <p>Registered automatically for every servlet service via {@link CommonSecurityAutoConfiguration}.
 */
public class TenantTelemetryFilter extends OncePerRequestFilter implements Ordered {

    static final String ORG_ID = "org_id";
    static final String USER_ID = "user_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String orgId = clean(request.getHeader("X-Org-Id"));
        String userId = clean(request.getHeader("X-User-Id"));

        if (orgId == null && userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Tempo: attributes on the active server span.
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            if (orgId != null) span.setAttribute(ORG_ID, orgId);
            if (userId != null) span.setAttribute(USER_ID, userId);
        }

        // Downstream propagation: baggage carried on outgoing (auto-instrumented) calls.
        BaggageBuilder bb = Baggage.current().toBuilder();
        if (orgId != null) bb.put(ORG_ID, orgId);
        if (userId != null) bb.put(USER_ID, userId);
        Context ctx = Context.current().with(bb.build());

        // Loki: MDC on every log line emitted during this request.
        if (orgId != null) MDC.put(ORG_ID, orgId);
        if (userId != null) MDC.put(USER_ID, userId);

        try (Scope ignored = ctx.makeCurrent()) {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ORG_ID);
            MDC.remove(USER_ID);
        }
    }

    /** Trim and normalise a header; treat blank / literal "null" as absent. */
    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        return (t.isEmpty() || "null".equals(t)) ? null : t;
    }

    @Override
    public int getOrder() {
        // Run late (inside the OTel server span and after identity resolution), before the controller.
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
