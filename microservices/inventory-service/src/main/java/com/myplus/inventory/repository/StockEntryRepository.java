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
    @Query("SELECT se FROM StockEntry se WHERE se.productId = :productId AND " + SCOPE
            + " AND (se.expiryDate IS NULL OR se.expiryDate >= :today)"
            + " ORDER BY CASE WHEN se.expiryDate IS NULL THEN 1 ELSE 0 END, se.expiryDate ASC, se.id ASC")
    List<StockEntry> findForFefo(@Param("productId") Long productId, @Param("orgId") Long orgId,
                                 @Param("userId") Long userId, @Param("today") LocalDate today);

    // Public storefront availability (slice 49 follow-up): per-product sellable quantity for a store (org). Mirrors
    // what the reservation allocator can actually hold — (quantity − reserved) over non-expired batches — so the
    // storefront never offers more than a checkout could reserve. Returns [productId, available] rows.
    @Query("SELECT se.productId, SUM(se.quantity - COALESCE(se.reservedQuantity, 0)) FROM StockEntry se "
            + "WHERE se.organizationId = :orgId AND (se.expiryDate IS NULL OR se.expiryDate >= :today) "
            + "GROUP BY se.productId")
    List<Object[]> availableByOrg(@Param("orgId") Long orgId, @Param("today") LocalDate today);
}
