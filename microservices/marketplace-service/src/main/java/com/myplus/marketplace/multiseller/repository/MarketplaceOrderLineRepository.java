package com.myplus.marketplace.multiseller.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;

public interface MarketplaceOrderLineRepository extends JpaRepository<MarketplaceOrderLine, Long> {

    /** idx_mkt_line_seller_order. */
    List<MarketplaceOrderLine> findBySellerOrderIdOrderByIdAsc(Long sellerOrderId);

    /** One query for a page of seller orders (no N+1). */
    List<MarketplaceOrderLine> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);

    /**
     * MKT-1g — the settlement sweeper: lines in {@code statuses} whose seller order has been DELIVERED, oldest first
     * (idx_mkt_line_settlement). A line never delivered is not the sweeper's business. MKT-3a: nor is MaxTheService's
     * own stock (PLATFORM): its sale is already MaxTheService's, in the warehouse's books; nothing is owed or paid out.
     */
    @org.springframework.data.jpa.repository.Query("select l from MarketplaceOrderLine l, MarketplaceSellerOrder so "
            + "where so.id = l.sellerOrderId and so.deliveredAt is not null and l.settlementStatus in :statuses "
            + "and (l.stockSourceType is null or l.stockSourceType <> 'PLATFORM') order by l.id")
    List<MarketplaceOrderLine> findDeliveredInStatus(
            @org.springframework.data.repository.query.Param("statuses") Collection<String> statuses,
            org.springframework.data.domain.Pageable page);

    /**
     * MKT-1g — a seller's ELIGIBLE lines not yet in a payout: what a payout request takes. MKT-5c: lines settle to their
     * payee, which for consigned stock the warehouse sold is the OWNER.
     */
    @org.springframework.data.jpa.repository.Query("select l from MarketplaceOrderLine l where l.settlementStatus = :status "
            + "and l.payoutId is null and (case when l.stockSourceType = 'CONSIGNMENT' then l.stockOwnerOrganizationId "
            + "else l.sellerOrganizationId end) = :org")
    List<MarketplaceOrderLine> findBySellerOrganizationIdAndSettlementStatusAndPayoutIdIsNull(
            @org.springframework.data.repository.query.Param("org") Long sellerOrganizationId,
            @org.springframework.data.repository.query.Param("status") String settlementStatus);

    /** MKT-1g — the lines a payout settles. */
    List<MarketplaceOrderLine> findByPayoutId(Long payoutId);

    /**
     * MKT-1g — the seller's statement: lines of orders it ACCEPTED, newest first. Lines of orders it rejected or let
     * expire were never sales, so they are not on a statement. MKT-5c: a consigned line is on its OWNER's statement
     * (the payee), not the warehouse's.
     */
    @org.springframework.data.jpa.repository.Query(value = "select l from MarketplaceOrderLine l, MarketplaceSellerOrder so "
            + "where so.id = l.sellerOrderId and (case when l.stockSourceType = 'CONSIGNMENT' then l.stockOwnerOrganizationId else l.sellerOrganizationId end) = :org and so.acceptanceStatus in :accepted "
            + "and (l.stockSourceType is null or l.stockSourceType <> 'PLATFORM') and (:status is null or l.settlementStatus = :status) order by l.id desc",
            countQuery = "select count(l) from MarketplaceOrderLine l, MarketplaceSellerOrder so "
            + "where so.id = l.sellerOrderId and (case when l.stockSourceType = 'CONSIGNMENT' then l.stockOwnerOrganizationId else l.sellerOrganizationId end) = :org and so.acceptanceStatus in :accepted "
            + "and (l.stockSourceType is null or l.stockSourceType <> 'PLATFORM') and (:status is null or l.settlementStatus = :status)")
    org.springframework.data.domain.Page<MarketplaceOrderLine> statement(
            @org.springframework.data.repository.query.Param("org") Long sellerOrganizationId,
            @org.springframework.data.repository.query.Param("accepted") Collection<String> acceptedStatuses,
            @org.springframework.data.repository.query.Param("status") String settlementStatus,
            org.springframework.data.domain.Pageable page);

    /**
     * MKT-5c — consigned units of one owner's product promised to shoppers but not yet sold: lines of parts still
     * waiting for the warehouse (UNASSIGNED while the checkout runs, OFFERED until it answers). What a listing can
     * sell is the ledger balance less this.
     */
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(l.quantity), 0) from MarketplaceOrderLine l, MarketplaceSellerOrder so "
            + "where so.id = l.sellerOrderId and l.stockSourceType = 'CONSIGNMENT' and l.stockOwnerOrganizationId = :owner "
            + "and l.custodianOrganizationId = :custodian and l.mktProductId = :product and so.acceptanceStatus in ('UNASSIGNED', 'OFFERED')")
    long consignedPromised(@org.springframework.data.repository.query.Param("owner") Long owner,
            @org.springframework.data.repository.query.Param("custodian") Long custodian,
            @org.springframework.data.repository.query.Param("product") Long product);
}
