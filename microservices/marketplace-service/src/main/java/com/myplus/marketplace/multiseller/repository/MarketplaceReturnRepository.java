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

    /** MKT-1g — the returns of many lines at once (the settlement sweeper and the statement, no N+1). */
    List<MarketplaceReturn> findByOrderLineIdIn(Collection<Long> orderLineIds);

    /**
     * MKT-2e — per seller, the returns whose cost the seller itself bears (R13.1: wrong product, not as described, …),
     * on parts placed since {@code from}, not refused. Rows: [sellerOrgId, count].
     */
    @org.springframework.data.jpa.repository.Query("select r.sellerOrgId, count(r) from MarketplaceReturn r, MarketplaceOrderLine l, "
            + "MarketplaceSellerOrder so where r.orderLineId = l.id and l.sellerOrderId = so.id and so.createdAt >= :from "
            + "and r.bearerOrgId = r.sellerOrgId and r.status <> 'REJECTED' group by r.sellerOrgId")
    List<Object[]> sellerFaultReturns(@org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from);
}
