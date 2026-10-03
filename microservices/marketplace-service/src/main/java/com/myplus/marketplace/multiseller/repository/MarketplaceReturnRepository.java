package com.myplus.marketplace.multiseller.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceReturn;

/** MKT-1f — returns (V30). */
public interface MarketplaceReturnRepository extends JpaRepository<MarketplaceReturn, Long> {

    Optional<MarketplaceReturn> findByReturnNo(String returnNo);

    List<MarketplaceReturn> findByCaseIdOrderByIdAsc(Long caseId);

    List<MarketplaceReturn> findByMktOrderIdOrderByIdAsc(Long mktOrderId);

    /** A line already being returned. idx_mkt_return_line. */
    boolean existsByOrderLineIdAndStatusIn(Long orderLineId, Collection<String> statuses);

    /** A seller's approved returns waiting for "Item received". */
    List<MarketplaceReturn> findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(Long sellerOrgId, String status);
}
