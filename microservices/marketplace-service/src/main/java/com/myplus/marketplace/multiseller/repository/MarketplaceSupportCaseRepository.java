package com.myplus.marketplace.multiseller.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceSupportCase;

/** MKT-1f — support cases (V30). */
public interface MarketplaceSupportCaseRepository extends JpaRepository<MarketplaceSupportCase, Long> {

    Optional<MarketplaceSupportCase> findByCaseNo(String caseNo);

    /** The order's case that is still open — one conversation per order. idx_mkt_case_order. */
    Optional<MarketplaceSupportCase> findFirstByMktOrderIdAndStatusNotOrderByIdDesc(Long mktOrderId, String status);

    /** The operator's queue: urgent first, then oldest. idx_mkt_case_queue. */
    Page<MarketplaceSupportCase> findByStatusInOrderByUrgentDescCreatedAtAsc(Collection<String> statuses, Pageable page);

    /** Resolved cases are history: newest first. */
    Page<MarketplaceSupportCase> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses, Pageable page);

    /** A customer's cases, newest first. */
    List<MarketplaceSupportCase> findTop50ByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** A seller's open tasks — only its own org. idx_mkt_case_seller. */
    List<MarketplaceSupportCase> findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(Long sellerOrgId, String status);
}
