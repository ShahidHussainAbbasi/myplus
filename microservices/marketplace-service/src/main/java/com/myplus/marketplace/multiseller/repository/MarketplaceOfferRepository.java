package com.myplus.marketplace.multiseller.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;

public interface MarketplaceOfferRepository extends JpaRepository<MarketplaceOffer, Long> {

    /** Anti-IDOR single read: an offer id from the browser resolves only inside the caller's org. */
    Optional<MarketplaceOffer> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<MarketplaceOffer> findByOrganizationIdAndMktProductId(Long organizationId, Long mktProductId);

    /** Seller list — idx_mkt_offer_org_created. */
    Page<MarketplaceOffer> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable page);

    /** Operator queue — idx_mkt_offer_status_created. */
    Page<MarketplaceOffer> findByApprovalStatusOrderByCreatedAtAsc(String approvalStatus, Pageable page);

    /** A seller's suspension or reinstatement re-publishes all its offers (bounded: one offer per product). */
    List<MarketplaceOffer> findByOrganizationId(Long organizationId);
}
