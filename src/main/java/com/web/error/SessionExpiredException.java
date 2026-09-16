package com.web.error;

/**
 * This session's JWT can no longer be revived: auth-service does not recognise the refresh token it holds.
 *
 * <h3>The incident this exists for</h3>
 * A production shopkeeper's sale was refused on 2026-09-16 with {@code Invalid refresh token} → 401. Their
 * oldest login had been evicted by the session cap ({@code jwt.max-sessions-per-user}, 5) to make room for a
 * sixth, and the screen said only <i>"Invalid or expired token"</i>. Worse, nothing cleared the dead session,
 * so every later click failed exactly the same way — the till was unusable until someone thought to sign out
 * and back in, which nothing on screen suggested.
 *
 * <h3>Why a typed exception rather than clearing the token store</h3>
 * {@code GatewayClient} decides between gateway mode and LEGACY direct-call mode on
 * {@code tokenStore.hasAccessToken()}. Clearing the store to force a login would instead make the next call
 * bypass the gateway entirely — no Bearer token, identity passed as plain headers. The failure has to travel
 * as a typed signal that {@link SessionExpiredAdvice} turns into a 401 the browser can act on, and that
 * invalidates the HTTP session on the way out.
 *
 * <h3>Scope — only an UNKNOWN token</h3>
 * Thrown when auth-service answers that it does not know this refresh token, i.e. the row is gone. A refresh
 * that fails because auth-service was briefly unreachable, or answered without a token, is NOT this: those
 * are transient, the session is still good, and bouncing a cashier to the login page for a hiccup would be a
 * worse defect than the one being fixed.
 */
public class SessionExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionExpiredException(String message) {
        super(message);
    }
}
