package com.myplus.marketplace.multiseller.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplacePayout;

public interface MarketplacePayoutRepository extends JpaRepository<MarketplacePayout, Long> {

    Optional<MarketplacePayout> findByIdempotencyKey(String idempotencyKey);

    /** idx_mkt_payout_seller — the seller's payout still in flight (at most one, by service rule). */
    Optional<MarketplacePayout> findFirstByOrganizationIdAndStatusIn(Long organizationId, Collection<String> statuses);

    /** idx_mkt_payout_status — the operator's queue. */
    Page<MarketplacePayout> findByStatusInOrderByRequestedAtDesc(Collection<String> statuses, Pageable page);

    Page<MarketplacePayout> findByOrganizationIdOrderByRequestedAtDesc(Long organizationId, Pageable page);
}
