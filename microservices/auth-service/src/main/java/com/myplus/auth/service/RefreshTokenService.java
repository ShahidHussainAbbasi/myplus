package com.myplus.auth.service;

import com.myplus.auth.entity.RefreshToken;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.RefreshTokenRepository;
import com.myplus.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${jwt.refresh-token-expiration-ms:604800000}")
    private long refreshTokenExpirationMs;

    /**
     * How many devices one account may stay signed in on at once. Oldest is evicted beyond this.
     *
     * <p>A cap exists only to bound the table — every login inserts a row, so without one a long-lived
     * account accumulates rows forever. Five is chosen to cover the realistic worst case for this
     * platform (till + back office + owner's phone + laptop, plus one spare) without being a limit an
     * honest user trips over. Configurable, because a shop with six tills is not doing anything wrong.
     */
    @Value("${jwt.max-sessions-per-user:5}")
    private int maxSessionsPerUser;

    /**
     * Start a NEW session for this user, leaving their other devices signed in.
     *
     * <p>This used to update the user's single row in place, which meant a second login silently
     * destroyed the first device's ability to refresh — that device then 401'd on everything once its
     * access token aged out ~15 minutes later. One row per session is the standard model and is what
     * {@code V6__refresh_token_per_session.sql} makes possible by dropping {@code UNIQUE(user_id)}.
     *
     * <p>Eviction is oldest-first and happens BEFORE the insert, so the cap is a true ceiling rather
     * than one-over. Deleting the surplus in the same transaction is safe here — unlike the old
     * delete+insert, nothing is racing a unique key on {@code user_id}, because there no longer is one.
     */
    @Transactional
    public RefreshToken createRefreshToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        List<RefreshToken> sessions = refreshTokenRepository.findByUserOrderByExpiryDateAsc(user);
        int surplus = sessions.size() - (maxSessionsPerUser - 1);   // room for the one about to be made
        for (int i = 0; i < surplus && i < sessions.size(); i++) {
            refreshTokenRepository.delete(sessions.get(i));         // oldest first
            /*
             * SESS-1 — SAY SO. This delete used to be silent, and that silence is why the cause of a
             * production incident on 2026-09-16 took a day to name: a shopkeeper's till answered "Invalid
             * refresh token" — the message thrown ONLY when the row is gone — and BOTH a logout-all
             * (AUTH-SESS-1 defect A, the CONFIRMED cause: the user confirmed somebody signed out of that
             * account ~15 minutes before) and a cap eviction delete rows, with nothing in any log to tell
             * them apart. One line per eviction rules this one in or out in seconds.
             */
            log.warn("Session cap reached for user {}: evicting the oldest of {} sessions (cap {}). "
                    + "That device will 401 once its access token expires.",
                    userId, sessions.size(), maxSessionsPerUser);
        }

        RefreshToken token = RefreshToken.builder().user(user).build();
        token.setToken(UUID.randomUUID().toString() + "-" + UUID.randomUUID());
        token.setExpiryDate(Instant.now().plusMillis(refreshTokenExpirationMs));
        return refreshTokenRepository.save(token);
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(token);
            throw new ValidationException("Refresh token expired. Please login again.");
        }
        return token;
    }

    @Transactional
    public void deleteByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        refreshTokenRepository.deleteByUser(user);
    }

    // ── SESS-1: the cap made visible, and clearable ────────────────────────────────────────────────

    /**
     * What the signed-in user's own header chip reports: how many devices, out of how many allowed, and
     * when the oldest of them signed in.
     *
     * <p>⚠ The oldest session is the one the cap evicts next, and eviction is by EXPIRY — which is issue
     * order, because {@code AuthService.refreshToken} re-mints the access token and hands back the SAME
     * refresh token rather than rotating it. So the device that goes is the one that signed in longest ago,
     * however hard it is being used: a till signed in on Monday loses to a tab opened this morning. That is
     * the fact this screen exists to make visible.
     *
     * <p>No device name, browser or "last used" is reported, because {@code refresh_tokens} does not carry
     * them — a count and a timestamp are what this table can honestly answer.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> describeSessions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        /*
         * ⚠ P6 (2026-09-16) — a COUNT and ONE row, not the whole list.
         *
         * This loaded every refresh-token row for the user via findByUserOrderByExpiryDateAsc and then used
         * exactly two things: how many there were, and the first one. The repository's countByUser had been
         * added for precisely this and was never called — its own comment claimed the chip "costs one COUNT per
         * read" while the code beneath it did the opposite. Small per call, and this is the call every signed-in
         * dashboard makes, so it is the shape that turns a convenience query into load.
         */
        // int, not long: this is bounded by the cap, and the chip's JSON shape is already gated — a slice
        // that changes a field's type on the wire while claiming to be a query optimisation is two changes.
        int count = (int) refreshTokenRepository.countByUser(user);
        Optional<RefreshToken> oldest = count == 0
                ? Optional.empty()
                : refreshTokenRepository.findFirstByUserOrderByExpiryDateAsc(user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", count);
        out.put("max", maxSessionsPerUser);
        out.put("atCap", count >= maxSessionsPerUser);
        // The oldest row's issue time, derived from its expiry — the table stores no createdAt.
        out.put("oldestSignedInAt", oldest
                .map(t -> t.getExpiryDate().minusMillis(refreshTokenExpirationMs).toString())
                .orElse(null));
        return out;
    }

    /**
     * End every OTHER session for this user, keeping the one that asked.
     *
     * <p>{@code keepToken} is the refresh token the calling session already holds server-side; it is never
     * read from a request body the browser could shape. A caller with no token of their own would be asking
     * to sign themselves out, so that is refused rather than interpreted.
     *
     * @return how many devices were signed out
     */
    @Transactional
    public int revokeOtherSessions(Long userId, String keepToken) {
        if (keepToken == null || keepToken.isBlank()) {
            throw new ValidationException("This session has no token of its own to keep.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        int removed = refreshTokenRepository.deleteByUserAndTokenNot(user, keepToken);
        if (removed > 0) {
            log.info("Signed out {} other session(s) for user {} at the user's own request.", removed, userId);
        }
        return removed;
    }

    // ── AUTH-SESS-1: end THIS session ─────────────────────────────────────────────────────────────

    /**
     * End ONE session: the device that presented this refresh token. The mirror of {@link #revokeOtherSessions} —
     * that one keeps the caller and drops the rest, this one drops the caller and keeps the rest — and between them
     * every delete of a row is now something somebody asked for.
     *
     * <p>The token IS the session's identity, and nothing else can be: the JWT carries {@code subject},
     * {@code issuedAt} and {@code expiration} but no session or device id, and {@code refresh_tokens} has no device
     * column. So a logout that wants to end "this device" must present the token that device holds.
     *
     * <p><b>Ownership is checked, not assumed</b> (anti-IDOR, as everywhere else here): another user's token deletes
     * nothing. An unknown, foreign or already-rotated token is not an error either — the session it names is gone
     * regardless — so this reports false and the caller still answers 200. A sign-out that throws leaves somebody
     * signed in, which is the one outcome a logout must never produce.
     *
     * <p>⚠ Do NOT fall back to {@link #deleteByUserId} when this returns false. That fallback is exactly the defect
     * AUTH-SESS-1 removes: one device's stale token would take every other device down with it.
     *
     * @return true if a session was ended
     */
    @Transactional
    public boolean deleteByToken(Long userId, String refreshToken) {
        if (userId == null || refreshToken == null || refreshToken.isBlank()) return false;
        return refreshTokenRepository.findByToken(refreshToken)
                .filter(t -> t.getUser() != null && userId.equals(t.getUser().getId()))
                .map(t -> {
                    refreshTokenRepository.delete(t);
                    return true;
                })
                .orElseGet(() -> {
                    // Worth a line: it means a device logged out holding a token this service does not know, which
                    // is what a displaced or already-rotated session looks like from here.
                    log.info("Logout for user {} presented a refresh token that matched no session of theirs.", userId);
                    return false;
                });
    }
}
