package com.myplus.inventory.repository;

import com.myplus.inventory.entity.StockLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped per-product stock state (slice 33, Phase 5b). */
@Repository
public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    String SCOPE = "(sl.organizationId = :orgId OR (sl.organizationId IS NULL AND sl.userId = :userId))";

    @Query("SELECT sl FROM StockLevel sl WHERE sl.productId = :productId AND " + SCOPE)
    Optional<StockLevel> findByProductScoped(@Param("productId") Long productId, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT sl FROM StockLevel sl WHERE " + SCOPE)
    List<StockLevel> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Task #20 — the tenant's stock valued at cost, as ONE aggregate row.
     *
     * <p><b>What this number IS, precisely:</b> {@code StockLevel.costPrice} is written by the purchase path
     * as {@code bpurchaseRate} — the rate of the LATEST purchase of that product. So this is stock valued at
     * LAST PURCHASE RATE, not a weighted average and not FIFO. It revalues the whole shelf at the newest
     * price, so it drifts from the ledger whenever the buying price moves. The dashboard tile must say so:
     * an unqualified "stock value" that disagrees with the books is worse than no tile, because it is the
     * kind of figure people trust without checking.
     *
     * <p>Rows with no {@code costPrice} are EXCLUDED rather than counted as zero — a product that has never
     * been purchased has an unknown cost, and treating unknown as free understates the total silently.
     *
     * <p>Summed in SQL. A dashboard KPI that loaded every StockLevel to multiply in Java is exactly the work
     * this endpoint was brought from ~640ms down by not doing.
     */
    @Query("SELECT COALESCE(SUM(sl.currentStock * sl.costPrice), 0) FROM StockLevel sl "
         + "WHERE sl.costPrice IS NOT NULL AND sl.currentStock IS NOT NULL AND " + SCOPE)
    java.math.BigDecimal sumStockValueScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT COUNT(sl) FROM StockLevel sl WHERE " + SCOPE)
    long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT sl FROM StockLevel sl WHERE sl.minStockLevel IS NOT NULL AND sl.currentStock <= sl.minStockLevel AND " + SCOPE)
    List<StockLevel> findLowStockScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT sl FROM StockLevel sl WHERE sl.currentStock <= 0 AND " + SCOPE)
    List<StockLevel> findOutOfStockScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    // SYSTEM/SCHEDULED (cross-tenant): the hourly AlertService job runs with no security context.
    @Query("SELECT sl FROM StockLevel sl WHERE sl.minStockLevel IS NOT NULL AND sl.currentStock <= sl.minStockLevel")
    List<StockLevel> findLowStock();
}
