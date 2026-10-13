package com.myplus.auth.repository;

import com.myplus.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);

    /** TEAM-1 — a new link cancels every older one for the person (only the newest e-mail works). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PasswordResetToken t WHERE t.user.id = :userId")
    int deleteAllForUser(@org.springframework.data.repository.query.Param("userId") Long userId);
}
