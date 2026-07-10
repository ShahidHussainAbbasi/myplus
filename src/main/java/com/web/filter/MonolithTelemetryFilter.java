package com.web.filter;

import com.persistence.model.User;
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
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Monolith counterpart of the services' {@code TenantTelemetryFilter}. The monolith is the
 * browser-facing trace root, so this stamps the logged-in {@code user_id} onto the current span,
 * the logs (MDC), and OpenTelemetry baggage — making the UI's spans/logs attributable and letting
 * the identity propagate to the gateway on outgoing calls.
 *
 * <p>{@code org_id} is intentionally not stamped here: the monolith holds only the opaque JWT (the
 * active org lives inside it), while each downstream service already stamps {@code org_id} from the
 * gateway's {@code X-Org-Id} header. All OpenTelemetry API calls no-op when no SDK is present.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 5)
public class MonolithTelemetryFilter extends OncePerRequestFilter {

    private static final String USER_ID = "user_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = currentUserId();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }

        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            span.setAttribute(USER_ID, userId);
        }

        BaggageBuilder bb = Baggage.current().toBuilder().put(USER_ID, userId);
        Context ctx = Context.current().with(bb.build());

        MDC.put(USER_ID, userId);
        try (Scope ignored = ctx.makeCurrent()) {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(USER_ID);
        }
    }

    private static String currentUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof User u && u.getId() != null) {
            return String.valueOf(u.getId());
        }
        return null;
    }
}
