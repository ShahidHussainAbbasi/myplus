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

    /** MKT-1f — the seller order behind a store order (idx_mkt_so_store_order, V30). */
    Optional<MarketplaceSellerOrder> findFirstByStoreOrderId(Long storeOrderId);

    /** The sweeper — idx_mkt_so_sweep: OFFERED past their deadline, oldest first, bounded. */
    List<MarketplaceSellerOrder> findByAcceptanceStatusAndAcceptByBeforeOrderByAcceptByAsc(String acceptanceStatus,
            LocalDateTime before, Pageable page);

    /** The sweeper's second pass: UNASSIGNED rows a crash left between the two checkout transactions. */
    List<MarketplaceSellerOrder> findByAcceptanceStatusAndCreatedAtBefore(String acceptanceStatus, LocalDateTime before,
            Pageable page);

    /** Release retries — idx_mkt_so_held: rows whose promise ended but whose hold may still stand. */
    List<MarketplaceSellerOrder> findByHeldTrueAndAcceptanceStatusIn(java.util.Collection<String> statuses, Pageable page);

    /**
     * MKT-2e — every part placed since {@code from}, as the scorecard reads it (idx_mkt_so_created, V33): seller, status,
     * placed, decided, delivered, the longest promise of its lines, and its MKT-2b record's party and status (null when
     * none; a part has at most one, uk_mkt_shortage_part). Columns in {@code SellerPerformance.Part} order.
     */
    @org.springframework.data.jpa.repository.Query("select so.sellerOrganizationId, so.acceptanceStatus, so.createdAt, so.decidedAt, "
            + "so.deliveredAt, (select max(l.promiseHours) from MarketplaceOrderLine l where l.sellerOrderId = so.id), "
            + "sh.responsibleRole, sh.status from MarketplaceSellerOrder so "
            + "left join MarketplaceShortage sh on sh.sellerOrderId = so.id where so.createdAt >= :from")
    List<Object[]> performanceFacts(@org.springframework.data.repository.query.Param("from") LocalDateTime from);

    /** The same for one seller. */
    @org.springframework.data.jpa.repository.Query("select so.sellerOrganizationId, so.acceptanceStatus, so.createdAt, so.decidedAt, "
            + "so.deliveredAt, (select max(l.promiseHours) from MarketplaceOrderLine l where l.sellerOrderId = so.id), "
            + "sh.responsibleRole, sh.status from MarketplaceSellerOrder so "
            + "left join MarketplaceShortage sh on sh.sellerOrderId = so.id "
            + "where so.createdAt >= :from and so.sellerOrganizationId = :org")
    List<Object[]> performanceFactsOf(@org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("org") Long org);
}
