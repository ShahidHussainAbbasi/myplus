package com.myplus.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * Why a sign-in was refused — a STABLE CODE that crosses the wire as {@code ApiResponse.reason} (2026-10-14).
 *
 * <p>Clients decide what to show from the CODE, never from {@code message}. The monolith used to match on this
 * service's English sentences ({@code body.contains("locked")}), which broke silently whenever a sentence was
 * reworded, and could not be translated. {@code message} stays as the developer-facing default for API callers
 * and logs; the person reads a message-bundle text keyed by the code.
 *
 * <p>⚠ The names are a CONTRACT with {@code com.security.SignInFailure} in the monolith: rename one, rename both.
 * The HTTP statuses are the ones these refusals already had, so no existing client sees a change.
 */
public enum SignInRefusal {

    BAD_CREDENTIALS(HttpStatus.BAD_REQUEST, "Invalid credentials"),
    ACCOUNT_LOCKED(HttpStatus.BAD_REQUEST, "Account is locked. Try again later."),
    NOT_VERIFIED(HttpStatus.BAD_REQUEST, "Account not verified. Please check your email to verify your account."),
    INVALID_2FA(HttpStatus.BAD_REQUEST, "Invalid 2FA code"),
    CAPTCHA_FAILED(HttpStatus.BAD_REQUEST, "Captcha verification failed"),
    /** TEAM-1: every membership the person held was removed by its owner. */
    ACCESS_REMOVED(HttpStatus.UNAUTHORIZED, "Your access to this business was removed. Ask the owner if this is a mistake.");

    private final HttpStatus status;
    private final String defaultMessage;

    SignInRefusal(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }

    public String defaultMessage() { return defaultMessage; }
}
