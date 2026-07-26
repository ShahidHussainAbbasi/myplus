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

   // Paged overload (slice 24) — LIMIT/OFFSET via Pageable, no count query.
   @Query("select c from Customer c where c.organizationId = :orgId "
        + "or (c.organizationId is null and c.userId = :userId)")
   List<Customer> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

   // OWN rows only (role-aware: a non-SUPER caller sees just the customers they created).
   @Query("select c from Customer c where c.userId = :userId "
        + "and (c.organizationId = :orgId or c.organizationId is null)")
   List<Customer> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // Store credit: update ONLY the cached credit_balance (targeted, not a full-entity save — a full save can rewrite
   // other columns to null when the entity isn't fully loaded, as it did on the vendor side).
   @org.springframework.data.jpa.repository.Modifying
   @Query(value = "update customer set credit_balance = :bal where customer_id = :id", nativeQuery = true)
   void updateCreditBalance(@Param("id") Long id, @Param("bal") java.math.BigDecimal bal);

   // Party bridge: stamp ONLY party_id (targeted — never a full-entity save, which could clobber other columns).
   @org.springframework.data.jpa.repository.Modifying
   @Query(value = "update customer set party_id = :partyId where customer_id = :id", nativeQuery = true)
   void updatePartyId(@Param("id") Long id, @Param("partyId") Long partyId);

   // P4 contact-view backfill: already-bridged rows, walked by an id cursor so the admin job can resume in batches.
   @Query("select c from Customer c where c.partyId is not null and c.customerId > :afterId "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId)) "
        + "order by c.customerId asc")
   List<Customer> findBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId,
                                   @Param("userId") Long userId, Pageable pageable);

   @Query("select count(c) from Customer c where c.partyId is not null and c.customerId > :afterId "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId))")
   long countBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
