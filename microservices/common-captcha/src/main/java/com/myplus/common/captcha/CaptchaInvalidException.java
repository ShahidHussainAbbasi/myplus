package com.myplus.common.captcha;

/** The captcha token was missing, malformed, throttled, or rejected by Google (slice 33, Phase 9). */
public class CaptchaInvalidException extends RuntimeException {

    public CaptchaInvalidException(final String message) {
        super(message);
    }
}
