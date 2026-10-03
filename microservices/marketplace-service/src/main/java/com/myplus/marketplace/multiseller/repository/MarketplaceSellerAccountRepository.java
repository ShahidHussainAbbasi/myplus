package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;

public interface MarketplaceSellerAccountRepository extends JpaRepository<MarketplaceSellerAccount, Long> {

    /** The tenant path: the caller's own account, by the JWT org. */
    Optional<MarketplaceSellerAccount> findByOrganizationId(Long organizationId);

    /** The operator's queue — served by idx_mkt_seller_status_applied. Paged; never an unbounded list (OMS-7). */
    Page<MarketplaceSellerAccount> findByStatusOrderByAppliedAtAsc(String status, Pageable page);

    Page<MarketplaceSellerAccount> findAllByOrderByAppliedAtDesc(Pageable page);
}
