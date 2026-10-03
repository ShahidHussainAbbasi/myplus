package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;

public interface MarketplaceSellerOrderRepository extends JpaRepository<MarketplaceSellerOrder, Long> {

    /** A seller reads/acts on ITS OWN seller orders only: another org's id is simply absent. */
    Optional<MarketplaceSellerOrder> findByIdAndSellerOrganizationId(Long id, Long sellerOrganizationId);

    /** The seller's queue — idx_mkt_so_seller_queue. */
    Page<MarketplaceSellerOrder> findBySellerOrganizationIdAndAcceptanceStatusOrderByCreatedAtDesc(Long sellerOrganizationId,
            String acceptanceStatus, Pageable page);

    Page<MarketplaceSellerOrder> findBySellerOrganizationIdOrderByCreatedAtDesc(Long sellerOrganizationId, Pageable page);

    /** The parent's seller orders (Phase 1: exactly one). */
    List<MarketplaceSellerOrder> findByMktOrderId(Long mktOrderId);

    /** The sweeper — idx_mkt_so_sweep: OFFERED past their deadline, oldest first, bounded. */
    List<MarketplaceSellerOrder> findByAcceptanceStatusAndAcceptByBeforeOrderByAcceptByAsc(String acceptanceStatus,
            LocalDateTime before, Pageable page);

    /** The sweeper's second pass: UNASSIGNED rows a crash left between the two checkout transactions. */
    List<MarketplaceSellerOrder> findByAcceptanceStatusAndCreatedAtBefore(String acceptanceStatus, LocalDateTime before,
            Pageable page);

    /** Release retries — idx_mkt_so_held: rows whose promise ended but whose hold may still stand. */
    List<MarketplaceSellerOrder> findByHeldTrueAndAcceptanceStatusIn(java.util.Collection<String> statuses, Pageable page);
}
