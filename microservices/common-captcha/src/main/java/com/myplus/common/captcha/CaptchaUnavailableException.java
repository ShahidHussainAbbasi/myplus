package com.myplus.common.captcha;

/** Google's siteverify could not be reached (transport/timeout) — distinct from an invalid token. */
public class CaptchaUnavailableException extends RuntimeException {

    public CaptchaUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
