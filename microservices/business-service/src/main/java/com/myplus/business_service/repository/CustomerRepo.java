/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Customer;

/**
 * @author sabbasi
 *
 */
public interface CustomerRepo extends JpaRepository<Customer, Long>,QueryByExampleExecutor<Customer> {


   List<Customer> findByUserId(Long userId);

   // Tenant-scoped read with NULL-fallback: own org's rows, plus pre-migration rows (org NULL) that
   // belong to the caller. The NULL set drains as those rows are re-saved with an organization_id.
   @Query("select c from Customer c where c.organizationId = :orgId "
        + "or (c.organizationId is null and c.userId = :userId)")
   List<Customer> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
