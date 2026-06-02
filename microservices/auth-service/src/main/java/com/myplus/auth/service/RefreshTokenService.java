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
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${jwt.refresh-token-expiration-ms:604800000}")
    private long refreshTokenExpirationMs;

    @Transactional
    public RefreshToken createRefreshToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        refreshTokenRepository.findByUser(user).ifPresent(refreshTokenRepository::delete);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString() + "-" + UUID.randomUUID())
                .expiryDate(Instant.now().plusMillis(refreshTokenExpirationMs))
                .build();
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
