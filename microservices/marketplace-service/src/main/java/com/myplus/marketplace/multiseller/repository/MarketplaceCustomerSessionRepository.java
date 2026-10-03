package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.marketplace.multiseller.entity.MarketplaceCustomerSession;

public interface MarketplaceCustomerSessionRepository extends JpaRepository<MarketplaceCustomerSession, Long> {

    /** uk_mkt_session_token — the SHA-256 of the token, never the token. */
    Optional<MarketplaceCustomerSession> findByTokenHash(String tokenHash);

    /** A password change ends every session of the account. */
    @Modifying
    @Query("update MarketplaceCustomerSession s set s.revokedAt = :at where s.customerId = :customerId and s.revokedAt is null")
    int revokeAll(@Param("customerId") Long customerId, @Param("at") LocalDateTime at);
}
