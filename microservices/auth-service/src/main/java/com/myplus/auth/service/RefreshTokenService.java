package com.myplus.auth.service;

import com.myplus.auth.entity.RefreshToken;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.RefreshTokenRepository;
import com.myplus.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
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
}
