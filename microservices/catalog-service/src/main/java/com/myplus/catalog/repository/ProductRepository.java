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
     * How many products this tenant holds in each category \u2014 the dashboard's category card.
     *
     * <h3>LEFT JOIN, so uncategorised products are a BUCKET and not an omission</h3>
     * A tenant here has 368 of 1,518 products with no category. An inner join would drop them, and the card
     * would then total less than the Products figure beside it \u2014 two numbers disagreeing on one dashboard,
     * which is the failure a summary card exists to avoid. The null row is returned and the caller names it.
     *
     * <h3>ACTIVE only, matching what the drill-through shows</h3>
     * The product grid hides deactivated rows by default, so a card counting them would promise more rows
     * than clicking it delivers. {@code isActive IS NULL} counts as active \u2014 the same reading the list
     * projection already applies to legacy rows, so the two cannot disagree.
     *
     * <p>Returns {@code [categoryId, categoryName, count]} rows, biggest first: the card order IS the
     * ranking, so a shop sees where its catalogue actually sits without reading the numbers.
     */
    @Query("SELECT c.id, c.name, COUNT(p) FROM Product p LEFT JOIN p.category c "
            + "WHERE " + SCOPE + " AND (p.isActive IS NULL OR p.isActive = TRUE) "
            + "GROUP BY c.id, c.name ORDER BY COUNT(p) DESC")
    java.util.List<Object[]> countByCategoryScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * ONB-3 — how many products carry a policy the tenant may be about to lose.
     *
     * <p>Counted in the database rather than by loading products and filtering: the answer is a number for a
     * confirmation dialog, and org 13 alone holds 1,101 products.
     *
     * <p>Uses the same SCOPE as every other read here, so a migration preview can never see across tenants
     * even though the caller is a platform operator asking about somebody else's org — the org id arrives as
     * a parameter and is used exactly as a tenant's own read would use it.
     */
    @Query("SELECT COUNT(p) FROM Product p WHERE " + SCOPE + " AND p.requiresSerial = TRUE")
    long countRequiringSerial(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT COUNT(p) FROM Product p WHERE " + SCOPE + " AND p.tracksBatch = TRUE")
    long countTrackingBatch(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * ONB-3 — the products a switch would strand, so the cleanup list can name them.
     *
     * <p>The LIST, not just the count: a warning an operator cannot act on is advice, not a feature. Bounded
     * by the caller's page size, because a tenant with a thousand serial-tracked products needs a worklist,
     * not a wall.
     */
    @Query("SELECT p FROM Product p WHERE " + SCOPE + " AND p.requiresSerial = TRUE ORDER BY p.name")
    List<Product> findRequiringSerial(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT p FROM Product p WHERE " + SCOPE + " AND p.tracksBatch = TRUE ORDER BY p.name")
    List<Product> findTrackingBatch(@Param("orgId") Long orgId, @Param("userId") Long userId);

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
    @Query("SELECT new com.myplus.catalog.dto.ProductPickerDTO("
         + "p.id, p.name, p.sellingPrice, p.requiresSerial) "
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

    /**
     * DUP-1 — the product a given form-fill already created, if any. Two callers: the fast path in
     * {@code ProductService.create} (a repeat that arrives after the first one committed) and the replay in
     * {@code ProductController.create} (a repeat that raced it and lost on the unique index).
     *
     * <p>SCOPED, and that is a security property rather than tidiness: the answer is a whole product, so an
     * unscoped lookup would hand a caller who guessed a key another tenant's product. It also carries the
     * standard {@code organizationId IS NULL AND userId = :userId} fallback, so the fast path still replays for
     * a legacy unstamped row — which the UNIQUE index cannot cover, MySQL treating NULLs as distinct.
     */
    @Query("SELECT p FROM Product p WHERE p.idempotencyKey = :key AND " + SCOPE)
    Optional<Product> findByIdempotencyKeyScoped(@Param("key") String key,
                                                 @Param("orgId") Long orgId, @Param("userId") Long userId);

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

    /**
     * The paged, filtered product read \u2014 what backs the Product grid and the dashboard category drill.
     *
     * <h3>Why this query grew three clauses</h3>
     * It had NO caller before this slice (0 in the monolith, 0 in JS, 0 in other services), so widening it
     * broke nothing \u2014 but it could not have backed the grid as it stood, in three separate ways:
     *
     * <ol>
     *   <li><b>{@code q} matched only name and SKU.</b> The grid searched CLIENT-side across its rendered
     *       columns, which include <b>Category</b> and <b>Manufacturer</b>. Moving the search to the server
     *       without these would have quietly stopped "Samsung" finding anything \u2014 a regression that
     *       throws no error and looks like missing data. Barcode joins them because a scanned code landing
     *       in a search box is the one thing a counter does without thinking; it is not a grid column, so
     *       nothing regresses either way.</li>
     *   <li><b>No {@code isActive} filter.</b> The grid hides deactivated products, and the dashboard card
     *       counts active only. Without this the card would say 120 and the click would show 166 \u2014 the
     *       exact disagreement a summary card exists to prevent. {@code includeInactive} carries the grid's
     *       existing "Show inactive" toggle through to the server rather than re-deciding it here.</li>
     *   <li><b>The uncategorised bucket was UNREACHABLE.</b> {@code :categoryId IS NULL} already means "no
     *       category filter", so there was no value that could mean "the ones with no category" \u2014 and a
     *       tenant here has 368 of them, a card row that could be clicked and would return everything.
     *       {@code uncategorised=true} is a separate flag precisely because null is already taken.</li>
     * </ol>
     *
     * <p>A NULL {@code isActive} counts as active, matching {@code countByCategoryScoped} and the list
     * projection \u2014 legacy rows predate the column and must not vanish from the screen.
     */
    @Query("SELECT p FROM Product p LEFT JOIN p.category c WHERE "
            + "(:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%',:q,'%')) "
            + "         OR LOWER(p.sku) LIKE LOWER(CONCAT('%',:q,'%')) "
            + "         OR LOWER(p.barcode) LIKE LOWER(CONCAT('%',:q,'%')) "
            + "         OR LOWER(p.manufacturer) LIKE LOWER(CONCAT('%',:q,'%')) "
            + "         OR LOWER(c.name) LIKE LOWER(CONCAT('%',:q,'%'))) "
            + "AND (:categoryId IS NULL OR c.id = :categoryId) "
            + "AND (:uncategorised = FALSE OR c.id IS NULL) "
            + "AND (:includeInactive = TRUE OR p.isActive IS NULL OR p.isActive = TRUE) "
            + "AND (:minPrice IS NULL OR p.sellingPrice >= :minPrice) "
            + "AND (:maxPrice IS NULL OR p.sellingPrice <= :maxPrice) "
            + "AND " + SCOPE)
    Page<Product> searchScoped(@Param("q") String q, @Param("categoryId") Long categoryId,
                               @Param("uncategorised") boolean uncategorised,
                               @Param("includeInactive") boolean includeInactive,
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
