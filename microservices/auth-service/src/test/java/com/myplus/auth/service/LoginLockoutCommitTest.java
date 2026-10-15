package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.auth.dto.LoginRequest;
import com.myplus.auth.exception.SignInRefusedException;

/**
 * 2026-10-14 — login's refusals must not roll back the failed-attempt count. Without {@code noRollbackFor} the
 * wrong-password branch saved the count and threw it away again, and no account ever locked. A mock cannot show a
 * rollback, so this pins the declaration; the Cypress gate {@code auth/login-lockout} proves the behaviour.
 */
class LoginLockoutCommitTest {

    @Test
    void a_refused_sign_in_commits_what_it_recorded() throws Exception {
        Transactional tx = AuthService.class.getMethod("login", LoginRequest.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(SignInRefusedException.class);
    }
}
