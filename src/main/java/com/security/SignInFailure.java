package com.security;

/**
 * Why a sign-in on this site failed, and what the login page says about it (2026-10-14).
 *
 * <p>The first six names are auth-service's {@code SignInRefusal} codes, read from the {@code reason} field of its
 * refusal — ⚠ a CONTRACT: rename one side, rename both. The last two arise here. Each carries the message KEY the
 * page shows (translated by the bundle) and the page it returns to.
 *
 * <p>Replaces string-matching on auth-service's English ({@code body.contains("locked")}) and on our own exception
 * texts ({@code reason.equalsIgnoreCase("Access removed")}): a reworded sentence silently became "Invalid
 * Credentials", and none of it could be translated.
 */
public enum SignInFailure {

    BAD_CREDENTIALS("message.badCredentials", "/login?error=true"),
    ACCOUNT_LOCKED("auth.message.locked", "/login?error=true"),
    NOT_VERIFIED("auth.message.notVerified", "/login?unverified=true"),
    INVALID_2FA("auth.message.invalid2fa", "/login?twofa=true"),
    CAPTCHA_FAILED("auth.message.captchaFailed", "/login?error=true"),
    ACCESS_REMOVED("auth.message.accessRemoved", "/login?error=true"),
    /** The password was right and 2FA is on — ask for the code (a prompt, not an error). */
    TWO_FACTOR_REQUIRED("auth.message.twoFactorRequired", "/login?twofa=true"),
    /** auth-service could not be reached, or answered with no reason (a 5xx). */
    AUTH_UNAVAILABLE("auth.message.unavailable", "/login?error=true");

    private final String messageKey;
    private final String failureUrl;

    SignInFailure(String messageKey, String failureUrl) {
        this.messageKey = messageKey;
        this.failureUrl = failureUrl;
    }

    public String messageKey() { return messageKey; }

    public String failureUrl() { return failureUrl; }

    /**
     * auth-service's refusal code → ours. A code this build does not know yet (auth deployed ahead of the monolith)
     * is shown as a plain refusal rather than failing the sign-in page.
     */
    public static SignInFailure fromRefusal(String reason) {
        if (reason == null || reason.isBlank()) return BAD_CREDENTIALS;
        try {
            return valueOf(reason.trim());
        } catch (IllegalArgumentException unknown) {
            return BAD_CREDENTIALS;
        }
    }
}
