package com.myplus.auth.repository;

import com.myplus.auth.entity.RefreshToken;
import com.myplus.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    /** The session a presented token belongs to. Still unique — a token identifies exactly one session. */
    Optional<RefreshToken> findByToken(String token);

    /** Revoke every session for a user (logout-all, and the account-disabled path). */
    @Modifying
    int deleteByUser(User user);

    /**
     * All of a user's live sessions, oldest first.
     *
     * <p>Replaces {@code Optional<RefreshToken> findByUser}: there is now one row per session, so the
     * single-result form would throw {@code NonUniqueResultException} as soon as a user signed in on a
     * second device. Ordered by expiry, which — because every token is minted with the same TTL — is
     * issue order, so the head of the list is the oldest session and is what the session cap evicts.
     */
    List<RefreshToken> findByUserOrderByExpiryDateAsc(User user);
}
