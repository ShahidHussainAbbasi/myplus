package com.myplus.catalog.repository;

import com.myplus.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped reads (slice 33). Stock queries (low/out-of-stock, expiring) intentionally absent — those
 *  are inventory-service concerns now. */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM Product p WHERE " + SCOPE)
    Page<Product> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    // Public storefront (slice 47): active products for a store (by orgId — no JWT identity on a public call).
    java.util.List<Product> findByOrganizationIdAndIsActiveTrueOrderByNameAsc(Long organizationId);

    @Query("SELECT p FROM Product p WHERE p.id = :id AND " + SCOPE)
    Optional<Product> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND " + SCOPE)
    List<Product> findAllByIdScoped(@Param("ids") List<Long> ids, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT (COUNT(p) > 0) FROM Product p WHERE p.sku = :sku AND " + SCOPE)
    boolean existsBySkuScoped(@Param("sku") String sku, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.sku = :sku AND " + SCOPE)
    Optional<Product> findBySkuScoped(@Param("sku") String sku, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.category.id = :categoryId AND " + SCOPE)
    Page<Product> findByCategoryScoped(@Param("categoryId") Long categoryId, @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

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
}
