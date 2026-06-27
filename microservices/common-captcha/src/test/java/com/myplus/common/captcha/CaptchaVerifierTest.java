package com.myplus.common.captcha;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Slice 33, Phase 9 — pure unit tests (no network). Covers the branches reachable before the Google call:
 * disabled = no-op, blank/invalid token, and the failure throttle. The HTTP success/failure path is
 * exercised live against Google when captcha is enabled in an environment.
 */
class CaptchaVerifierTest {

    private static CaptchaVerifier verifier(boolean enabled, CaptchaAttemptService attempts) {
        CaptchaProperties props = new CaptchaProperties();
        props.setEnabled(enabled);
        props.setSecret("test-secret");
        return new CaptchaVerifier(props, attempts);
    }

    @Test
    void disabled_is_a_noop_even_with_a_blank_token() {
        CaptchaVerifier verifier = verifier(false, new CaptchaAttemptService());
        assertThatCode(() -> verifier.verify("", "1.2.3.4")).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(null, "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void enabled_rejects_a_blank_or_malformed_token_without_calling_google() {
        CaptchaVerifier verifier = verifier(true, new CaptchaAttemptService());
        assertThatThrownBy(() -> verifier.verify("", "1.2.3.4"))
                .isInstanceOf(CaptchaInvalidException.class);
        assertThatThrownBy(() -> verifier.verify("bad token with spaces!", "1.2.3.4"))
                .isInstanceOf(CaptchaInvalidException.class);
    }

    @Test
    void enabled_blocks_a_client_over_the_failure_threshold_before_calling_google() {
        CaptchaAttemptService attempts = new CaptchaAttemptService();
        for (int i = 0; i < CaptchaAttemptService.MAX_ATTEMPT; i++) {
            attempts.failed("9.9.9.9");
        }
        CaptchaVerifier verifier = verifier(true, attempts);
        // A syntactically valid token, but the IP is blocked — must fail fast, no network.
        assertThatThrownBy(() -> verifier.verify("valid_LOOKING-token123", "9.9.9.9"))
                .isInstanceOf(CaptchaInvalidException.class)
                .hasMessageContaining("maximum number of failed attempts");
    }
}
