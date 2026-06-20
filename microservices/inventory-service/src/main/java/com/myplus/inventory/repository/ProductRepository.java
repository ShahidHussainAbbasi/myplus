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

/**
 * Tenant-scoped reads (slice 33, Phase 4.5). Every list/get/exists query carries the standard NULL-fallback
 * predicate {@code (organizationId = :orgId OR (organizationId IS NULL AND userId = :userId))} so one
 * tenant never sees another's products and pre-migration org-NULL rows stay visible to their author.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // org-scoping fragment reused across the queries below.
    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM Product p WHERE " + SCOPE)
    Page<Product> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.id = :id AND " + SCOPE)
    Optional<Product> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE " + SCOPE)
    List<Product> findAllScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT COUNT(p) FROM Product p WHERE " + SCOPE)
    long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT (COUNT(p) > 0) FROM Product p WHERE p.sku = :sku AND " + SCOPE)
    boolean existsBySkuScoped(@Param("sku") String sku, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND " + SCOPE)
    Page<Product> findByCategoryScoped(@Param("categoryId") Long categoryId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.currentStock <= p.minStockLevel AND " + SCOPE)
    List<Product> findLowStockScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.currentStock <= 0 AND " + SCOPE)
    List<Product> findOutOfStockScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT DISTINCT p FROM Product p JOIN StockEntry se ON se.product = p "
            + "WHERE se.expiryDate BETWEEN :today AND :until AND " + SCOPE)
    List<Product> findExpiringScoped(@Param("today") LocalDate today, @Param("until") LocalDate until,
                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE "
            + "(:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%',:q,'%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%',:q,'%'))) "
            + "AND (:categoryId IS NULL OR p.category.id = :categoryId) "
            + "AND (:minPrice IS NULL OR p.sellingPrice >= :minPrice) "
            + "AND (:maxPrice IS NULL OR p.sellingPrice <= :maxPrice) "
            + "AND " + SCOPE)
    Page<Product> searchScoped(@Param("q") String q, @Param("categoryId") Long categoryId,
                               @Param("minPrice") java.math.BigDecimal minPrice,
                               @Param("maxPrice") java.math.BigDecimal maxPrice,
                               @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    // SYSTEM/SCHEDULED use only (cross-tenant): the hourly AlertService job runs with no security context
    // and must scan every tenant. Request-scoped callers MUST use findLowStockScoped/findOutOfStockScoped.
    @Query("SELECT p FROM Product p WHERE p.currentStock <= p.minStockLevel")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.currentStock <= 0")
    List<Product> findOutOfStockProducts();
}
