package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.auth.entity.RefreshToken;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.RefreshTokenRepository;
import com.myplus.auth.repository.UserRepository;

/**
 * SESS-1 — the session cap, and the two things a shopkeeper can now see and do about it.
 *
 * <p>Design: {@code microservices/docs/slices/sess-1-till-stays-signed-in.md}. The Cypress gate proves the
 * chip and the recovery end to end; these pin what a screen test cannot see — WHICH row the cap deletes,
 * that it stops deleting below the cap, and that "sign out my other devices" keeps exactly the caller's own.
 *
 * <p>The production incident behind it: a sale refused with "Invalid refresh token", which
 * {@code AuthService.refreshToken} throws ONLY when the row is ABSENT — i.e. evicted, not expired.
 */
class RefreshTokenServiceTest {

    private static final long TTL_MS = 604_800_000L;   // 7 days, the shipped jwt.refresh-token-expiration-ms

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private RefreshTokenService service(int cap) {
        RefreshTokenService s = new RefreshTokenService(tokens, users);
        ReflectionTestUtils.setField(s, "refreshTokenExpirationMs", TTL_MS);
        ReflectionTestUtils.setField(s, "maxSessionsPerUser", cap);
        return s;
    }

    private static User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    /** A session whose issue time is `agoMillis` in the past — expiry carries it, as the table has no createdAt. */
    private static RefreshToken session(long id, String token, long agoMillis) {
        return RefreshToken.builder()
                .id(id)
                .token(token)
                .expiryDate(Instant.now().plusMillis(TTL_MS - agoMillis))
                .build();
    }

    // ── the cap ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("at the cap the OLDEST session is evicted, and only it")
    void evictsOldestAtCap() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        // Five live sessions, oldest first — the order the repository promises.
        List<RefreshToken> live = new ArrayList<>(List.of(
                session(1, "oldest", 5_000), session(2, "b", 4_000), session(3, "c", 3_000),
                session(4, "d", 2_000), session(5, "newest", 1_000)));
        when(tokens.findByUserOrderByExpiryDateAsc(u)).thenReturn(live);
        when(tokens.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        service(5).createRefreshToken(74L);

        // Exactly one delete, and it is the head of the list — the device that signed in longest ago.
        verify(tokens, times(1)).delete(any(RefreshToken.class));
        verify(tokens).delete(live.get(0));
        verify(tokens).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("below the cap nothing is evicted — a second device must not cost the first")
    void keepsEveryoneBelowCap() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        when(tokens.findByUserOrderByExpiryDateAsc(u))
                .thenReturn(new ArrayList<>(List.of(session(1, "a", 2_000), session(2, "b", 1_000))));
        when(tokens.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        service(5).createRefreshToken(74L);

        verify(tokens, never()).delete(any(RefreshToken.class));
    }

    @Test
    @DisplayName("the new session carries the configured lifetime")
    void newSessionGetsTheTtl() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        when(tokens.findByUserOrderByExpiryDateAsc(u)).thenReturn(new ArrayList<>());
        when(tokens.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        RefreshToken made = service(5).createRefreshToken(74L);

        assertThat(made.getExpiryDate()).isAfter(Instant.now().plusMillis(TTL_MS - 60_000));
        assertThat(made.getToken()).isNotBlank();
    }

    // ── what the shopkeeper is shown ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("describeSessions reports the count, the cap, and whether a new sign-in would evict")
    void describesTheCap() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        /*
          * P6: the service now asks for a COUNT and the single OLDEST row, instead of loading every row and
          * using two things from the list. The ASSERTIONS below are untouched on purpose — what the shopkeeper
          * is shown must not change because the queries behind it did.
          */
        when(tokens.countByUser(u)).thenReturn(3L);
        when(tokens.findFirstByUserOrderByExpiryDateAsc(u)).thenReturn(Optional.of(session(3, "c", 1_000)));

        Map<String, Object> out = service(5).describeSessions(74L);

        assertThat(out.get("count")).isEqualTo(3);
        assertThat(out.get("max")).isEqualTo(5);
        assertThat(out.get("atCap")).isEqualTo(false);
        assertThat(out.get("oldestSignedInAt")).isNotNull();
    }

    @Test
    @DisplayName("atCap is true at the cap — the warning the till needed before it lost a sale")
    void atCapIsTrueAtTheCap() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        when(tokens.countByUser(u)).thenReturn(5L);
        when(tokens.findFirstByUserOrderByExpiryDateAsc(u)).thenReturn(Optional.of(session(5, "e", 1_000)));

        assertThat(service(5).describeSessions(74L).get("atCap")).isEqualTo(true);
    }

    @Test
    @DisplayName("no sessions: a count of zero, and no oldest to report")
    void describesAnEmptyAccount() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        when(tokens.countByUser(u)).thenReturn(0L);

        Map<String, Object> out = service(5).describeSessions(74L);

        assertThat(out.get("count")).isEqualTo(0);
        assertThat(out.get("oldestSignedInAt")).isNull();
        // An empty account must not ask for an oldest row at all — the count already answered the question.
        verify(tokens, never()).findFirstByUserOrderByExpiryDateAsc(u);
    }

    // ── sign out my other devices ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("revoke-others keeps EXACTLY the calling session")
    void revokeOthersKeepsTheCaller() {
        User u = user(74L);
        when(users.findById(74L)).thenReturn(Optional.of(u));
        when(tokens.deleteByUserAndTokenNot(u, "mine")).thenReturn(4);

        assertThat(service(5).revokeOtherSessions(74L, "mine")).isEqualTo(4);

        // The caller's own token is the one held back — never a user id from a request.
        verify(tokens).deleteByUserAndTokenNot(eq(u), eq("mine"));
        verify(tokens, never()).deleteByUser(any(User.class));
    }

    @Test
    @DisplayName("a caller with no token of its own is refused, not signed out")
    void revokeOthersRefusesWithoutAToken() {
        assertThatThrownBy(() -> service(5).revokeOtherSessions(74L, "  "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no token");

        verify(tokens, never()).deleteByUserAndTokenNot(any(User.class), any());
        verify(tokens, never()).deleteByUser(any(User.class));
    }

    // ── AUTH-SESS-1: logout ends THIS device ──────────────────────────────────────────────────────
    //
    // The confirmed production defect: an ordinary sign-out called deleteByUser and took every device with it.
    // The till kept working on its unexpired access token and was refused ~15 minutes later, mid-sale, with
    // "Invalid refresh token". Every case here asserts deleteByUser is NOT called — that is the regression.

    @Test
    @DisplayName("⭐ logout deletes the presenting session and NOTHING else")
    void deleteByTokenEndsOnlyThatSession() {
        User u = user(74L);
        RefreshToken mine = session(2, "mine", 1_000);
        mine.setUser(u);
        when(tokens.findByToken("mine")).thenReturn(Optional.of(mine));

        assertThat(service(5).deleteByToken(74L, "mine")).isTrue();

        verify(tokens).delete(mine);
        verify(tokens, times(1)).delete(any(RefreshToken.class));
        verify(tokens, never()).deleteByUser(any(User.class));   // ⭐ the defect, pinned
    }

    @Test
    @DisplayName("⭐ another user's token deletes nothing — a logout cannot end a stranger's session")
    void deleteByTokenRefusesAForeignToken() {
        RefreshToken theirs = session(3, "theirs", 1_000);
        theirs.setUser(user(99L));
        when(tokens.findByToken("theirs")).thenReturn(Optional.of(theirs));

        assertThat(service(5).deleteByToken(74L, "theirs")).isFalse();

        verify(tokens, never()).delete(any(RefreshToken.class));
        verify(tokens, never()).deleteByUser(any(User.class));
    }

    @Test
    @DisplayName("an unknown or already-rotated token is not an error, and takes no other session down")
    void deleteByTokenToleratesAnUnknownToken() {
        when(tokens.findByToken("stale")).thenReturn(Optional.empty());

        assertThat(service(5).deleteByToken(74L, "stale")).isFalse();

        verify(tokens, never()).delete(any(RefreshToken.class));
        verify(tokens, never()).deleteByUser(any(User.class));   // ⚠ never fall back to revoke-all
    }

    @Test
    @DisplayName("no token supplied: nothing is read, nothing is deleted — the caller decides what that means")
    void deleteByTokenIgnoresABlankToken() {
        assertThat(service(5).deleteByToken(74L, "   ")).isFalse();
        assertThat(service(5).deleteByToken(74L, null)).isFalse();
        assertThat(service(5).deleteByToken(null, "mine")).isFalse();

        verify(tokens, never()).findByToken(any());
        verify(tokens, never()).delete(any(RefreshToken.class));
        verify(tokens, never()).deleteByUser(any(User.class));
    }
}
