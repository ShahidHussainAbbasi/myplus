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

    @Query("SELECT se FROM StockEntry se WHERE se.product.id = :productId AND " + SCOPE)
    Page<StockEntry> findByProductScoped(@Param("productId") Long productId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT se FROM StockEntry se WHERE se.warehouse.id = :warehouseId AND " + SCOPE)
    Page<StockEntry> findByWarehouseScoped(@Param("warehouseId") Long warehouseId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT se FROM StockEntry se WHERE se.product.id = :productId AND se.warehouse.id = :warehouseId AND " + SCOPE)
    List<StockEntry> findByProductAndWarehouseScoped(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT se FROM StockEntry se WHERE se.expiryDate BETWEEN :today AND :until AND " + SCOPE)
    List<StockEntry> findExpiringScoped(@Param("today") LocalDate today, @Param("until") LocalDate until,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);
}
