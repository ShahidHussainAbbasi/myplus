package com.service;

/**
 * Application facade for the forget/reset password flow. Hides the fact that the auth-service
 * (not the monolith DB) owns the user store and the reset token, and concentrates the error
 * policy so controllers stay thin. Implemented by {@link PasswordResetFacadeImpl} over
 * {@link com.web.util.AuthServerClient}.
 */
public interface PasswordResetFacade {

    /**
     * Trigger a password-reset email for {@code email}. Always succeeds from the caller's point of
     * view — failures (unknown address, transport) are swallowed so the UI never reveals whether an
     * address is registered. {@code captchaResponse} is forwarded to the auth-service for verification.
     */
    void requestReset(String email, String captchaResponse);

    /**
     * Complete a password reset using the token from the reset email.
     *
     * @throws com.web.error.PasswordResetException if the token is invalid/expired or the new
     *         password is rejected by the auth-service.
     */
    void completeReset(String token, String newPassword);
}
