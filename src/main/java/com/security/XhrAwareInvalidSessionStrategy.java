package com.security;

import java.io.IOException;

import org.springframework.security.web.session.InvalidSessionStrategy;
import org.springframework.security.web.session.SimpleRedirectInvalidSessionStrategy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SESS-2 — an expired session answers a SCRIPT with 401 {@code SESSION_EXPIRED}, and a PAGE with the Session expired page.
 *
 * <h3>The defect this closes</h3>
 * "Session expiry shows a parser error instead of the login page" (Test Book §13). {@code invalidSessionUrl} answered
 * every request on a dead session with a 302 to {@code /invalidSession.html}. A browser navigating is meant to land
 * there; jQuery is not — it follows the redirect silently, receives a 200 HTML page where it asked for JSON, and fails
 * with {@code parsererror}. {@code handleAjaxFailure} (main.js) recognises a 401, the LOGIN page and the concurrent-
 * session notice as a lost session, but not this page, so the person was shown "form submit: parsererror (200)".
 *
 * <h3>Why the answer is the SESS-1 shape</h3>
 * {@code SessionExpiredAdvice} already answers a dead downstream token with exactly this body, and main.js already
 * turns it into a redirect to {@code /login} carrying {@code ui.js.sessionEnded} in the person's language. Reusing it
 * means one client path for both ways a session can end, rather than teaching the browser a third page to sniff.
 *
 * <h3>Why a new session is created on the script branch too</h3>
 * {@link SimpleRedirectInvalidSessionStrategy} creates one before redirecting, and that is load-bearing: without it
 * the browser keeps sending the dead id, and the {@code /login} page it is sent to would itself be answered as an
 * invalid session. The script branch keeps the same behaviour.
 */
public class XhrAwareInvalidSessionStrategy implements InvalidSessionStrategy {

    static final String BODY = "{\"success\":false,\"code\":\"SESSION_EXPIRED\","
            + "\"message\":\"Your session has ended. Sign in again.\"}";

    private final InvalidSessionStrategy pages;

    public XhrAwareInvalidSessionStrategy(String invalidSessionUrl) {
        SimpleRedirectInvalidSessionStrategy redirect = new SimpleRedirectInvalidSessionStrategy(invalidSessionUrl);
        redirect.setCreateNewSession(true);
        this.pages = redirect;
    }

    @Override
    public void onInvalidSessionDetected(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!isScript(request)) {
            pages.onInvalidSessionDetected(request, response);
            return;
        }
        request.getSession();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(BODY);
    }

    /**
     * A request made by script rather than by a person navigating. jQuery marks every same-origin call with
     * {@code X-Requested-With}; a {@code fetch} that asks for JSON and not for HTML is treated the same way. A browser
     * navigation always lists {@code text/html}, so it can never be taken for a script.
     */
    static boolean isScript(HttpServletRequest request) {
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) return true;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("text/html");
    }
}
