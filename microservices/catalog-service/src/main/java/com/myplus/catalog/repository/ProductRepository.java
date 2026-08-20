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

    /** M4e.c (slice 103): tenant-scoped product count (for the dashboard KPI, replacing the local Item count). */
    @Query("SELECT COUNT(p) FROM Product p WHERE " + SCOPE)
    long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * PERF-8 — the product picker's read: ACTIVE rows only, projected to three columns.
     *
     * <p>A constructor expression rather than {@code SELECT p}, deliberately. Selecting the entity loads all
     * 23 columns — including {@code description}, which is {@code varchar(2000)} — and then throws 20 of them
     * away in the mapper. This projects in SQL, so the wide columns never leave the database.
     *
     * <p>{@code isActive} is filtered here rather than in the browser: every caller previously downloaded the
     * deactivated products and hid them in JavaScript.
     *
     * <p>{@code Boolean.TRUE} is compared explicitly because the column is a nullable {@code Boolean} — a
     * pre-migration row with {@code NULL} is not active and must not appear in a till's picker.
     */
    @Query("SELECT new com.myplus.catalog.dto.ProductPickerDTO(p.id, p.name, p.sellingPrice) "
         + "FROM Product p WHERE p.isActive = TRUE AND " + SCOPE + " ORDER BY p.name ASC")
    Page<com.myplus.catalog.dto.ProductPickerDTO> findPickerScoped(@Param("orgId") Long orgId,
                                                                   @Param("userId") Long userId,
                                                                   Pageable pageable);

    // Public storefront (slice 47): active products for a store (by orgId — no JWT identity on a public call).
    java.util.List<Product> findByOrganizationIdAndIsActiveTrueOrderByNameAsc(Long organizationId);

    // Public storefront search (slice 60): active products whose name contains the query, case-insensitive.
    java.util.List<Product> findByOrganizationIdAndIsActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(
            Long organizationId, String name);

    @Query("SELECT p FROM Product p WHERE p.id = :id AND " + SCOPE)
    Optional<Product> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND " + SCOPE)
    List<Product> findAllByIdScoped(@Param("ids") List<Long> ids, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT (COUNT(p) > 0) FROM Product p WHERE p.sku = :sku AND " + SCOPE)
    boolean existsBySkuScoped(@Param("sku") String sku, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE p.sku = :sku AND " + SCOPE)
    Optional<Product> findBySkuScoped(@Param("sku") String sku, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Duplicate-NAME guard for the product form (case-insensitive). SKU is optional, so the duplicate that
     * actually happens — same name, no code — was caught by nothing; this is what the form's focus-out check asks.
     *
     * <p>The match is ORG-WIDE, not per-user: {@link #SCOPE}'s leading clause is {@code organizationId = :orgId},
     * so a product any colleague in the tenant registered is found. That is the point of the check — the operator
     * about to create a twin is usually NOT the one who created the original. The trailing
     * {@code organizationId IS NULL AND userId = :userId} leg is only the pre-migration fallback shared by every
     * scoped read here; a legacy row with no org stamped is still visible only to the user who created it, so a
     * namesake among those is not reported. (Same limit as findScoped/existsBySkuScoped — not new to this query.)
     *
     * <p>Deactivated namesakes are returned too (registering a second "Panadol 500mg" because the first was
     * deactivated is exactly the case worth naming), but an ACTIVE match sorts first so the "edit this one
     * instead" link lands on the live product. A NULL isActive counts as active, as the rest of the stack reads it.
     */
    @Query("SELECT p FROM Product p WHERE LOWER(TRIM(p.name)) = LOWER(:name) AND " + SCOPE
         + " ORDER BY CASE WHEN p.isActive = false THEN 1 ELSE 0 END, p.id ASC")
    List<Product> findByNameScoped(@Param("name") String name, @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Barcode-first sell: exact scan lookup by barcode OR sku, active only, tenant-scoped. Barcode match preferred
     *  (ordered first) — returned as a list so an ambiguous code (one product's barcode == another's sku) can't throw. */
    @Query("SELECT p FROM Product p WHERE (p.barcode = :code OR p.sku = :code) AND p.isActive = true AND " + SCOPE
         + " ORDER BY CASE WHEN p.barcode = :code THEN 0 ELSE 1 END, p.id ASC")
    List<Product> findByCodeScoped(@Param("code") String code, @Param("orgId") Long orgId, @Param("userId") Long userId);

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


    /**
     * Slice I2 — which of these product NAMES already exist in this tenant, in ONE query.
     *
     * <p>Name is the import's duplicate key: {@code sku} is optional (2026-08-20) and a key that is
     * sometimes absent is not a key, whereas {@code name} is required on every row.
     *
     * <p>Deliberately a batched {@code IN} over a projection, never one query per row — {@code existsBySkuScoped}
     * is right for a single save and would be O(n) round trips for a 2 000-row import, which is the shape
     * {@code CustomerController.addCustomer}'s in-memory full scan already has.
     *
     * <p>Served by {@code idx_products_org_name} (V10), shipped in the same migration as this method.
     */
    @Query("SELECT p.name FROM Product p WHERE p.name IN :names AND " + SCOPE)
    List<String> existingNamesScoped(@Param("names") java.util.Collection<String> names,
                                     @Param("orgId") Long orgId,
                                     @Param("userId") Long userId);

}
