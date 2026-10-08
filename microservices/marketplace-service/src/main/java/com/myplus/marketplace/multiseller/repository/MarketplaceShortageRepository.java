package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceShortage;

/** MKT-2b — unfulfilled parts and what became of them (V32). */
public interface MarketplaceShortageRepository extends JpaRepository<MarketplaceShortage, Long> {

    /** One record per part (uk_mkt_shortage_part). */
    Optional<MarketplaceShortage> findBySellerOrderId(Long sellerOrderId);

    List<MarketplaceShortage> findByMktOrderIdOrderByIdAsc(Long mktOrderId);

    List<MarketplaceShortage> findByMktOrderIdIn(Collection<Long> mktOrderIds);

    List<MarketplaceShortage> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);

    /** The sweeper: proposals nobody answered in time. idx_mkt_shortage_sweep. */
    List<MarketplaceShortage> findByResultAndProposalExpiresAtBefore(String result, LocalDateTime before, Pageable page);

    /** The sweeper: a resolution a crash interrupted. */
    List<MarketplaceShortage> findByResultAndCreatedAtBefore(String result, LocalDateTime before, Pageable page);

    /** The sweeper: a proposal's hold that a failed release left behind. */
    List<MarketplaceShortage> findByProposalHeldTrueAndResultNot(String result, Pageable page);

    /** The operator's list: by status, oldest first. idx_mkt_shortage_status. */
    Page<MarketplaceShortage> findByStatusInOrderByIdAsc(Collection<String> statuses, Pageable page);

    Page<MarketplaceShortage> findAllByOrderByIdDesc(Pageable page);
}
