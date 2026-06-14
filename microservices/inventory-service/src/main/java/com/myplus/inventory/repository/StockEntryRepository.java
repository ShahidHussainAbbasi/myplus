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

@Repository
public interface StockEntryRepository extends JpaRepository<StockEntry, Long> {
    Page<StockEntry> findByProductId(Long productId, Pageable pageable);
    Page<StockEntry> findByWarehouseId(Long warehouseId, Pageable pageable);

    @Query("SELECT se FROM StockEntry se WHERE se.product.id = :productId AND se.warehouse.id = :warehouseId")
    List<StockEntry> findByProductAndWarehouse(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    @Query("SELECT se FROM StockEntry se WHERE se.expiryDate BETWEEN :today AND :until")
    List<StockEntry> findExpiringStock(@Param("today") LocalDate today, @Param("until") LocalDate until);
}
