package com.web.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * SESS-1 — turns a session that can no longer be revived into an answer the browser can act on.
 *
 * <h3>What it replaces</h3>
 * A production shopkeeper's sale was refused on 2026-09-16 with the gateway's own words,
 * <i>"Invalid or expired token"</i>, and nothing more. The session was never cleared, so every later click
 * repeated the same failure — the till was unusable until somebody thought to sign out and back in, which
 * nothing on screen suggested.
 *
 * <p>Now: {@code 401 {success:false, code:"SESSION_EXPIRED", message:…}}, and the HTTP session is invalidated
 * on the way out so the next request starts clean. {@code ajax-overlay.js}'s global {@code ajaxError} hook
 * reads the code and sends the page to {@code /login}.
 *
 * <h3>⚠ @Order is LOAD-BEARING — without it this never runs</h3>
 * {@code RestResponseEntityExceptionHandler} declares {@code @ExceptionHandler(Exception.class)}, and
 * Spring's resolver walks advice beans IN ORDER, returning the FIRST that has any matching method — it does
 * not prefer the most specific handler across advices. An unordered advice therefore loses to the catch-all
 * and its 401 comes back as a generic 500. That is exactly how slice 3.1b shipped broken on 2026-08-06; see
 * {@link DownstreamNotFoundAdvice}, which carries the same annotation for the same reason.
 *
 * <h3>Why the session is invalidated HERE</h3>
 * No other advice in this package touches the session, so this is a deliberate first. It belongs here rather
 * than in {@code GatewayClient} because the client cannot safely clear the token store: it chooses between
 * gateway mode and legacy direct-call mode on {@code tokenStore.hasAccessToken()}, so emptying it would make
 * the next call bypass the gateway instead of forcing a login. See {@link SessionExpiredException}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SessionExpiredAdvice {

    @ExceptionHandler(SessionExpiredException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handle(SessionExpiredException ex, HttpServletRequest request) {
        /*
         * Invalidate rather than merely clear the TokenStore: the store is session-scoped, so ending the
         * session takes the dead tokens with it AND drops the Spring Security context, which is what makes
         * the next request land on the login page instead of half-authenticated.
         *
         * Best-effort by design — a failure to invalidate must not replace a handled refusal with a second
         * exception thrown from the error path, which is the rule ProxyErrors records for the same reason.
         */
        try {
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
        } catch (IllegalStateException alreadyGone) {
            // Another request on the same session invalidated it first. Nothing to do.
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "SESSION_EXPIRED");
        // The sentence the SCREEN shows is chosen in the browser (ui.js.sessionEnded, translated into all six
        // languages). This one is the fallback for a caller that does not read the code — never the gateway's
        // "Invalid or expired token", which is written for a developer.
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "Your session has ended. Sign in again.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
