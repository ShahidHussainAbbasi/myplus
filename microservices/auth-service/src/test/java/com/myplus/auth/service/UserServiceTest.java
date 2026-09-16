package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.myplus.auth.dto.ChangePasswordRequest;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.RoleRepository;
import com.myplus.auth.repository.UserRepository;

/**
 * AUTH-SESS-1 defect B — a credential change ends the sessions opened with the old credential.
 *
 * <p>Until this slice, {@code changePassword} and {@code lockUser} saved the user and stopped. Every existing
 * session stayed alive on its refresh token for up to {@code jwt.refresh-token-expiration-ms} (7 days) — including
 * the session the change was meant to shut out. The capability to revoke existed and was spelled
 * {@code deleteByUserId}; it was simply wired to the wrong event (an ordinary logout, defect A).
 *
 * <p>Design: {@code microservices/docs/slices/auth-sess-1-per-device-logout.md}. Kept out of Cypress on purpose: a
 * screen test that really changes an account's password risks locking that account out of every later run if the
 * change-back ever fails, and what matters here is which call is made, which a unit test can see exactly.
 */
class UserServiceTest {

    private static final Long USER_ID = 74L;

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RefreshTokenService sessions = mock(RefreshTokenService.class);

    private final UserService service = new UserService(users, roles, encoder, sessions);

    private User user() {
        User u = new User();
        u.setId(USER_ID);
        u.setPassword("ENCODED-OLD");
        return u;
    }

    private static ChangePasswordRequest change(String current, String next) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setCurrentPassword(current);
        r.setNewPassword(next);
        return r;
    }

    private static ChangePasswordRequest change(String current, String next, String refreshToken) {
        ChangePasswordRequest r = change(current, next);
        r.setRefreshToken(refreshToken);
        return r;
    }

    @Test
    @DisplayName("⭐⭐ P5 — changing a password keeps THIS session and signs out the others")
    void changePasswordKeepsTheCallersOwnSession() {
        User u = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        when(encoder.matches("old", "ENCODED-OLD")).thenReturn(true);
        when(encoder.encode("new")).thenReturn("ENCODED-NEW");

        service.changePassword(USER_ID, change("old", "new", "THIS-DEVICE"));

        verify(users).save(u);
        /*
         * The defect this pins: revoking ALL sessions here signed the user out of the device they were sitting
         * at — not immediately (the access token has ~15 minutes left) but a quarter of an hour later, by which
         * time nothing on screen connects the sign-out to the password change that caused it.
         */
        verify(sessions).revokeOtherSessions(USER_ID, "THIS-DEVICE");
        verify(sessions, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("⭐ a client that sends no refresh token still revokes EVERYTHING (the compatibility path)")
    void changePasswordWithoutATokenRevokesEverySession() {
        User u = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        when(encoder.matches("old", "ENCODED-OLD")).thenReturn(true);
        when(encoder.encode("new")).thenReturn("ENCODED-NEW");

        service.changePassword(USER_ID, change("old", "new"));

        verify(users).save(u);
        verify(sessions).deleteByUserId(USER_ID);
    }

    @Test
    @DisplayName("⭐ a WRONG current password revokes nothing — a failed attempt must not sign anyone out")
    void wrongCurrentPasswordRevokesNothing() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(encoder.matches("guess", "ENCODED-OLD")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(USER_ID, change("guess", "new")))
                .isInstanceOf(ValidationException.class);

        // Otherwise anyone who knows an email could sign a shop's tills out by guessing at the password form.
        // BOTH revoke paths are forbidden here, not just the one this method happens to take today: the check
        // that matters is "a failed attempt revokes nothing", and an assertion naming only one call would go
        // green if the other were ever moved above the password check.
        verify(sessions, never()).deleteByUserId(any());
        verify(sessions, never()).revokeOtherSessions(any(), any());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("locking an account also ends the sessions it already has")
    void lockUserRevokesEverySession() {
        User u = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));

        service.lockUser(USER_ID);

        // accountNonLocked is read at LOGIN, so without the revoke a lock would only stop the NEXT sign-in
        // while the tills already signed in kept trading.
        verify(users).save(u);
        verify(sessions).deleteByUserId(eq(USER_ID));
    }
}
