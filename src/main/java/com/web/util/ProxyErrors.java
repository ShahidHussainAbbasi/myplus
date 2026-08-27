package com.web.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a proxy controller returns when the downstream call failed.
 *
 * <h3>The bug this exists to end</h3>
 *
 * Every proxy method in this monolith ended in the same line:
 *
 * <pre>{@code
 * } catch (Exception e) {
 *     LOGGER.error("... proxy error", e);
 *     return Collections.singletonMap("status", "ERROR");   // <-- everything the server said is gone
 * }
 * }</pre>
 *
 * <p>That discards the reason. Two consequences, both reported by users rather than by tests:
 *
 * <ol>
 *   <li><b>The free-trial message never arrived.</b> The gateway answers a write from an expired trial with
 *       403 and a real sentence — <i>"Your free trial has ended. Upgrade at maxtheservice.com to continue."</i>
 *       {@code GatewayClient} turns it into a {@link com.web.error.DemoLimitException}, and
 *       {@link com.web.error.DemoLimitAdvice} renders the upsell — <b>but only if the exception escapes the
 *       controller.</b> A blanket {@code catch (Exception)} swallows it first, so the user saw an unexplained
 *       failure. This was fixed in ONE controller by hand; <b>108 other places still swallowed it.</b></li>
 *   <li><b>Ordinary downstream reasons were lost too</b> — "Product SKU already exists: 001", "Insufficient
 *       stock", a validation message. The screen said nothing useful, and the only copy was in a log.</li>
 * </ol>
 *
 * <h3>The rule</h3>
 *
 * <b>A refusal is an ANSWER, not a failure.</b> The service went to the trouble of explaining itself; the
 * proxy's job is to carry that sentence to the person who can act on it, not to replace it with a status.
 *
 * <p>Two shapes, because this codebase has two envelopes and unifying them is a separate change:
 * {@link #statusError} keeps {@code {status:"ERROR"}} and {@link #failure} keeps {@code {success:false}}.
 * <b>Both are strictly additive</b> — the field a caller already reads is unchanged, and {@code message} is
 * added when the downstream supplied one. No existing JS branch changes meaning.
 */
public final class ProxyErrors {

    private ProxyErrors() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * For proxies that answer in the {@code GenericResponse} shape: {@code {status, message}}.
     *
     * @throws com.web.error.DemoLimitException deliberately, so the trial/demo upsell reaches the browser
     */
    public static Map<String, Object> statusError(Exception e) {
        rethrowIfUserFacing(e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ERROR");
        String message = downstreamMessage(e);
        if (message != null) out.put("message", message);
        return out;
    }

    /** For proxies that answer in the {@code {success:false}} shape. Same rules, different field. */
    public static Map<String, Object> failure(Exception e) {
        rethrowIfUserFacing(e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        String message = downstreamMessage(e);
        if (message != null) out.put("message", message);
        return out;
    }

    /** The {@code {status:"ERROR"}} body with no exception to explain it. */
    public static Map<String, Object> statusError() {
        return Collections.singletonMap("status", "ERROR");
    }

    /**
     * Exceptions that already carry a message meant for the USER, and must reach their {@code @ControllerAdvice}.
     *
     * <p>Swallowing one of these replaces a sentence written for a shopkeeper with a status code written for a
     * developer.
     */
    private static void rethrowIfUserFacing(Exception e) {
        if (e instanceof com.web.error.DemoLimitException dle) throw dle;
    }

    /**
     * The {@code message} the downstream service actually sent, or null.
     *
     * <p>Best-effort by design: a body that is not JSON, or JSON without a message, must not turn a handled
     * failure into a second exception thrown from the error path.
     */
    @SuppressWarnings("unchecked")
    private static String downstreamMessage(Exception e) {
        if (e instanceof HttpStatusCodeException he) {
            try {
                Map<String, Object> body = MAPPER.readValue(he.getResponseBodyAsString(), Map.class);
                Object m = body.get("message");
                if (m != null && !String.valueOf(m).isBlank()) return String.valueOf(m);
                Object err = body.get("error");
                if (err != null && !String.valueOf(err).isBlank()) return String.valueOf(err);
            } catch (Exception ignored) {
                // Not a JSON body, or not one with a message. Fall through to the bare status.
            }
        }
        return null;
    }
}
