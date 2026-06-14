package com.web.error;

/**
 * Raised when the auth-service rejects a password-reset attempt (invalid or expired token, or a
 * new password that fails the auth-service policy). Carries a user-facing message.
 */
public final class PasswordResetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PasswordResetException(final String message) {
        super(message);
    }

    public PasswordResetException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
