package com.myplus.business_service.repository;

import com.myplus.business_service.entity.ItemCatalogMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemCatalogMapRepo extends JpaRepository<ItemCatalogMap, Long> {

    /** itemIds already migrated for an org — re-runs skip these (idempotency, slice 33 U2). */
    @Query("SELECT m.itemId FROM ItemCatalogMap m WHERE m.organizationId = :org")
    List<Long> findItemIdsByOrganizationId(@Param("org") Long org);

    /** Mapped items whose local Stock hasn't been seeded into inventory yet (slice 33, U2b). */
    List<ItemCatalogMap> findByOrganizationIdAndStockMigratedFalse(Long organizationId);

    /** Translate a business itemId to its catalog productId for the saga sell path (slice 33, U3b). */
    @Query("SELECT m.productId FROM ItemCatalogMap m WHERE m.itemId = :itemId AND m.organizationId = :org")
    java.util.Optional<Long> findProductIdByItemId(@Param("itemId") Long itemId, @Param("org") Long org);
}

