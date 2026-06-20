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
