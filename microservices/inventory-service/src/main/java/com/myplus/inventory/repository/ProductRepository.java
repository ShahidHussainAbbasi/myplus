package com.myplus.inventory.repository;

import com.myplus.inventory.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    Page<Product> findBySkuContainingIgnoreCaseOrNameContainingIgnoreCase(String sku, String name, Pageable pageable);
    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.currentStock <= p.minStockLevel")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.currentStock <= 0")
    List<Product> findOutOfStockProducts();

    @Query("SELECT DISTINCT p FROM Product p JOIN StockEntry se ON se.product = p WHERE se.expiryDate BETWEEN :today AND :until")
    List<Product> findExpiringProducts(@Param("today") LocalDate today, @Param("until") LocalDate until);

    @Query("SELECT p FROM Product p WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%',:q,'%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%',:q,'%'))) " +
            "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
            "AND (:minPrice IS NULL OR p.sellingPrice >= :minPrice) " +
            "AND (:maxPrice IS NULL OR p.sellingPrice <= :maxPrice)")
    Page<Product> search(@Param("q") String q, @Param("categoryId") Long categoryId,
                         @Param("minPrice") java.math.BigDecimal minPrice,
                         @Param("maxPrice") java.math.BigDecimal maxPrice, Pageable pageable);

    boolean existsBySku(String sku);
}
