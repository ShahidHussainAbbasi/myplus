package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;

public interface MarketplaceProductSourceRepository extends JpaRepository<MarketplaceProductSource, Long> {

    /** The seller's own proposal for one of its products (UNIQUE pair). */
    Optional<MarketplaceProductSource> findByOrganizationIdAndSourceProductId(Long organizationId, Long sourceProductId);

    /** MKT-1c: the seller's MATCHED source for one canonical product (idx_mkt_source_product narrows it). */
    Optional<MarketplaceProductSource> findFirstByOrganizationIdAndMktProductIdAndMatchStatus(Long organizationId,
            Long mktProductId, String matchStatus);

    /** Seller list — idx_mkt_source_org_created. */
    Page<MarketplaceProductSource> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable page);

    /** Operator queue, oldest waiting first — idx_mkt_source_status_created. */
    Page<MarketplaceProductSource> findByMatchStatusOrderByCreatedAtAsc(String matchStatus, Pageable page);

    /** Operator history of decided proposals, newest first — the same index, read backwards. */
    Page<MarketplaceProductSource> findByMatchStatusOrderByCreatedAtDesc(String matchStatus, Pageable page);
}
