package com.myplus.auth.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.myplus.auth.dto.ApiResponse;

/** 2026-10-14 — a refused sign-in carries its STABLE code in `reason`; clients branch on that, never on the text. */
class SignInRefusalTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void a_refusal_answers_its_own_status_with_its_code() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleSignInRefused(new SignInRefusedException(SignInRefusal.ACCESS_REMOVED));
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody().getReason()).isEqualTo("ACCESS_REMOVED");
        assertThat(r.getBody().isSuccess()).isFalse();
    }

    @Test
    void the_statuses_are_the_ones_these_refusals_already_had() {
        // unchanged for every existing client: only ACCESS_REMOVED (new with TEAM-1) is a 401
        for (SignInRefusal s : SignInRefusal.values()) {
            assertThat(s.status().value()).isEqualTo(s == SignInRefusal.ACCESS_REMOVED ? 401 : 400);
        }
    }

    @Test
    void a_captcha_refusal_carries_its_code_too() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleCaptchaInvalid(
                new com.myplus.common.captcha.CaptchaInvalidException("rejected"));
        assertThat(r.getBody().getReason()).isEqualTo("CAPTCHA_FAILED");
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }
}
