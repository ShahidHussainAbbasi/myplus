package com.myplus.business_service.repository;

import com.myplus.business_service.entity.ItemCatalogMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemCatalogMapRepo extends JpaRepository<ItemCatalogMap, Long> {

    // All three queries are null-org-safe: a caller with no active org (legacy org-NULL rows) matches the
    // org-NULL maps, while a tenant caller matches their org. ( :org IS NULL handles the SQL `= NULL` pitfall. )
    String SCOPE = "(m.organizationId = :org OR (:org IS NULL AND m.organizationId IS NULL))";  // interface field = public static final

    /** itemIds already migrated for an org — re-runs skip these (idempotency, slice 33 U2). */
    @Query("SELECT m.itemId FROM ItemCatalogMap m WHERE " + SCOPE)
    List<Long> findMappedItemIds(@Param("org") Long org);

    /** Mapped items whose local Stock hasn't been seeded into inventory yet (slice 33, U2b). */
    @Query("SELECT m FROM ItemCatalogMap m WHERE m.stockMigrated = false AND " + SCOPE)
    List<ItemCatalogMap> findUnmigratedStock(@Param("org") Long org);

    /** Translate a business itemId to its catalog productId for the saga sell path (slice 33, U3b). */
    @Query("SELECT m.productId FROM ItemCatalogMap m WHERE m.itemId = :itemId AND " + SCOPE)
    java.util.Optional<Long> findProductIdByItemId(@Param("itemId") Long itemId, @Param("org") Long org);

    /**
     * Reverse map: catalog productId -> business itemId, for resolving the item name of SAGA sells when
     * listing sales (saga Sell rows carry productId, not a local Stock/itemId). Batched: returns
     * [productId, itemId] pairs for the given products.
     */
    @Query("SELECT m.productId, m.itemId FROM ItemCatalogMap m WHERE m.productId IN :productIds AND " + SCOPE)
    List<Object[]> findItemIdsByProductIds(@Param("productIds") List<Long> productIds, @Param("org") Long org);
}

