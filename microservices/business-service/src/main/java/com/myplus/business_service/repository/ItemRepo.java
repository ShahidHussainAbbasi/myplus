/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Item;

/**
 * @author sabbasi
 *
 */
public interface ItemRepo extends JpaRepository<Item, Long>,QueryByExampleExecutor<Item> {

   // M3c (slice 82): distinct (org, user) of items NOT yet catalog-mapped — drives the deploy-time startup auto-migrate
   // so a pre-convergence DB maps its legacy items on boot (no manual /migrate-catalog). Empty in the normal case.
   @Query("SELECT DISTINCT i.organizationId, i.userId FROM Item i WHERE i.id NOT IN (SELECT m.itemId FROM ItemCatalogMap m)")
   List<Object[]> findUnmappedOrgUser();

   // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
   @Query("select i from Item i where i.organizationId = :orgId "
        + "or (i.organizationId is null and i.userId = :userId)")
   List<Item> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // Paged overload (slice 24).
   @Query("select i from Item i where i.organizationId = :orgId "
        + "or (i.organizationId is null and i.userId = :userId)")
   List<Item> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);
}
