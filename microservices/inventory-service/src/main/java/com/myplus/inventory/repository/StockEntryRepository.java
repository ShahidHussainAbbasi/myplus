package com.myplus.inventory.repository;

import com.myplus.inventory.entity.StockEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Tenant-scoped reads (slice 33, Phase 4.5). */
@Repository
public interface StockEntryRepository extends JpaRepository<StockEntry, Long> {

    String SCOPE = "(se.organizationId = :orgId OR (se.organizationId IS NULL AND se.userId = :userId))";

    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND " + SCOPE)
    Page<StockEntry> findByProductScoped(@Param("productId") Long productId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT se FROM StockEntry se WHERE se.warehouse.id = :warehouseId AND " + SCOPE)
    Page<StockEntry> findByWarehouseScoped(@Param("warehouseId") Long warehouseId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    // Purchase-edit reconcile: the batch a purchase created, matched by productId + batchNo. Newest first so an
    // edit reconciles the most recent matching lot when (rarely) a batchNo repeats.
    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND se.batchNo = :batchNo AND " + SCOPE
            + " ORDER BY se.id DESC")
    List<StockEntry> findByProductAndBatchScoped(@Param("productId") Long productId, @Param("batchNo") String batchNo,
                                                 @Param("orgId") Long orgId, @Param("userId") Long userId);

    // Purchase-edit reconcile fallback: all of a product's lots, newest first, so a DECREASE with no matching
    // batchNo draws down the most-recent receipts (keeps batch totals in step with on-hand).
    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND " + SCOPE + " ORDER BY se.id DESC")
    List<StockEntry> findByProductNewestFirst(@Param("productId") Long productId, @Param("orgId") Long orgId,
                                              @Param("userId") Long userId);

    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND se.warehouse.id = :warehouseId AND " + SCOPE)
    List<StockEntry> findByProductAndWarehouseScoped(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT se FROM StockEntry se WHERE se.expiryDate BETWEEN :today AND :until AND " + SCOPE)
    List<StockEntry> findExpiringScoped(@Param("today") LocalDate today, @Param("until") LocalDate until,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    // FEFO ordering (slice 33, Phase 6a): earliest expiry first; null-expiry (non-perishable) batches last,
    // then by id for a stable order. Used by the reservation allocator.
    // G1 (compliance, slice 33): EXCLUDE already-expired batches (expiryDate < today) so a sale/dispense never
    // allocates expired stock — if only expired batches remain, the allocator sees 0 available -> OUT_OF_STOCK.
    // P11 (slice 55): also exclude quarantined (restockable=false) entries — never allocate returned, non-sellable stock.
    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND " + SCOPE
            + " AND (se.expiryDate IS NULL OR se.expiryDate >= :today)"
            + " AND (se.restockable IS NULL OR se.restockable = true)"
            + " ORDER BY CASE WHEN se.expiryDate IS NULL THEN 1 ELSE 0 END, se.expiryDate ASC, se.id ASC")
    List<StockEntry> findForFefo(@Param("productId") Long productId, @Param("orgId") Long orgId,
                                 @Param("userId") Long userId, @Param("today") LocalDate today);

    // Public storefront availability (slice 49 follow-up): per-product sellable quantity for a store (org). Mirrors
    // what the reservation allocator can actually hold — (quantity − reserved) over non-expired batches — so the
    // storefront never offers more than a checkout could reserve. Returns [productId, available] rows.
    /** Quarantine register (slice 58): the org's non-sellable (returned) lots, newest first. */
    @Query("SELECT se FROM StockEntry se WHERE se.restockable = false AND " + SCOPE + " ORDER BY se.id DESC")
    List<StockEntry> findQuarantinedScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT se.productId, SUM(se.quantity - COALESCE(se.reservedQuantity, 0)) FROM StockEntry se "
            + "WHERE se.organizationId = :orgId AND (se.expiryDate IS NULL OR se.expiryDate >= :today) "
            + "AND (se.restockable IS NULL OR se.restockable = true) "   // P11: exclude quarantined stock
            + "GROUP BY se.productId")
    List<Object[]> availableByOrg(@Param("orgId") Long orgId, @Param("today") LocalDate today);

    // Stock screen honesty (sellable + expired badge): per product, SELLABLE = (qty − reserved) over non-expired,
    // non-quarantined batches (exactly what the FEFO allocator can hold), and EXPIRED = physical qty locked in
    // already-expired batches (present on the shelf but unsellable). Same NULL-fallback SCOPE as getAllLevels.
    // Returns [productId, sellable, expired] rows.
    // OMS O5a adds HELD as a third measure. Sellable was already net of reservedQuantity, so a stock hold
    // silently made `sellable` smaller with nothing anywhere to explain it: a shopkeeper saw "on-hand 16,
    // sellable 6", no expired batches, and had nowhere to look. Publishing the number that accounts for the
    // difference is the operator-facing half of the OMS-6 fix.
    // Returns [productId, sellable, expired, held] rows.
    @Query("SELECT se.productId, "
            + "SUM(CASE WHEN (se.expiryDate IS NULL OR se.expiryDate >= :today) AND (se.restockable IS NULL OR se.restockable = true) "
            + "         THEN (se.quantity - COALESCE(se.reservedQuantity, 0)) ELSE 0 END), "
            + "SUM(CASE WHEN se.expiryDate IS NOT NULL AND se.expiryDate < :today THEN se.quantity ELSE 0 END), "
            + "SUM(COALESCE(se.reservedQuantity, 0)) "
            + "FROM StockEntry se WHERE " + SCOPE + " GROUP BY se.productId")
    List<Object[]> sellableExpiredByScope(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                          @Param("today") LocalDate today);
}
