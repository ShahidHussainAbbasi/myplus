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

    /**
     * The seller's own offer of a product: every source but CONSIGNMENT. MKT-5c lets the warehouse hold one more offer
     * per product for each consignor (V42: unique per org, product and owner), which this never returns.
     */
    Optional<MarketplaceOffer> findFirstByOrganizationIdAndMktProductIdAndStockSourceTypeNot(Long organizationId,
            Long mktProductId, String stockSourceType);

    /** MKT-5c — the warehouse's listing of one consignor's stock of a product. */
    Optional<MarketplaceOffer> findByOrganizationIdAndMktProductIdAndStockOwnerOrganizationIdAndStockSourceType(
            Long organizationId, Long mktProductId, Long stockOwnerOrganizationId, String stockSourceType);

    /** Seller list — idx_mkt_offer_org_created. */
    Page<MarketplaceOffer> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable page);

    /** Operator queue — idx_mkt_offer_status_created. */
    Page<MarketplaceOffer> findByApprovalStatusOrderByCreatedAtAsc(String approvalStatus, Pageable page);

    /** Decided offers (history), newest first — idx_mkt_offer_status_created read backwards. */
    Page<MarketplaceOffer> findByApprovalStatusOrderByCreatedAtDesc(String approvalStatus, Pageable page);

    /** A seller's suspension or reinstatement re-publishes all its offers (bounded: one offer per product). */
    List<MarketplaceOffer> findByOrganizationId(Long organizationId);
}
