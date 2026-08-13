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

   /**
    * OMS O7 D2d — the outlets on a rep's round: the TERRITORY read behind the booking screen's picker.
    *
    * <p>Org-scoped, then narrowed to what this rep covers: their own assignments <b>plus every unassigned
    * outlet</b>. That second half is the platform's established rule for an absent grant — an outlet nobody
    * has been given is not hidden from everybody — and it is what lets a distributor who has configured no
    * territories work unchanged on day one.
    *
    * <p>Ordered by name because this drives a picker a person reads, not a report.
    */
   @Query("select c from Customer c where c.organizationId = :orgId "
        + "and (c.assignedRepUserId = :repId or c.assignedRepUserId is null) "
        + "order by c.name asc")
   List<Customer> findOutletsForRep(@Param("orgId") Long orgId, @Param("repId") Long repId);

   /** Every outlet in the org, for a whole-org viewer (owner/admin). Same ordering, same projection use. */
   @Query("select c from Customer c where c.organizationId = :orgId order by c.name asc")
   List<Customer> findOutletsForOrg(@Param("orgId") Long orgId);

   /**
    * ONE customer, tenant-scoped — the anti-IDOR read (O7 D2).
    *
    * <p>This repository had every LIST read scoped and no scoped SINGLE read, so any endpoint taking a
    * {@code customerId} from the request had only {@code findById}, which ignores the tenant entirely. That is
    * fine where the id was already proved to be ours (following a customer's own stamped credit-account id),
    * and a cross-tenant leak the moment the id comes from a URL. {@code /creditStanding} is the first endpoint
    * to take one that way, so this is what it uses: another tenant's customer reads as absent, which is the
    * platform's standard anti-IDOR shape.
    *
    * <p>Same NULL-fallback as {@link #findScoped} so pre-migration rows behave identically.
    */
   @Query("select c from Customer c where c.customerId = :id "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId))")
   java.util.Optional<Customer> findByIdScoped(@Param("id") Long id,
                                               @Param("orgId") Long orgId, @Param("userId") Long userId);

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

   // ── B2B Phase 4a — shared-pool credit ──────────────────────────────────────────────────────────────────────
   // Σ(due) across every customer drawing on one credit account. This is the SHARED POOL: a company's branches
   // all point at the company's row, so the group's exposure is one indexed SUM rather than a per-branch read.
   // A standalone customer is a single-member group, so this returns exactly its own due — unchanged behaviour.
   // Runs on the sell path; backed by idx_customer_org_credit_account (V36).
   @Query("select coalesce(sum(c.dueAmount), 0) from Customer c where c.creditAccountCustomerId = :accountId "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId))")
   java.math.BigDecimal sumDueByCreditAccount(@Param("accountId") Long accountId,
                                              @Param("orgId") Long orgId, @Param("userId") Long userId);

   // The customers bridged to a set of parties — how a hierarchy edit in party-service maps back to rows to
   // re-stamp. ONE query for the whole subtree instead of a lookup per party.
   @Query("select c from Customer c where c.partyId in :partyIds "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId))")
   List<Customer> findByPartyIdsScoped(@Param("partyIds") java.util.Collection<Long> partyIds,
                                       @Param("orgId") Long orgId, @Param("userId") Long userId);

   // Targeted stamp — never a full-entity save, matching updatePartyId/updateCreditBalance above (a partial
   // entity save has already clobbered other columns to null on the vendor side once).
   //
   // @Transactional HERE, unlike its two neighbours, because this one is called from a NON-transactional caller:
   // CustomerAccountService.setAccountParent makes party-service HTTP calls and must not hold a pooled DB
   // connection across them. A @Modifying query with no active transaction throws TransactionRequiredException,
   // so the boundary has to live somewhere — on the repo method is the narrowest place that works.
   @org.springframework.data.jpa.repository.Modifying
   @org.springframework.transaction.annotation.Transactional
   @Query(value = "update customer set credit_account_customer_id = :accountId where customer_id = :id",
          nativeQuery = true)
   void updateCreditAccount(@Param("id") Long id, @Param("accountId") Long accountId);

   // Trade customers that never bridged to a party — they cannot join a hierarchy, and the §7 risk says the
   // feature must SHOW them rather than silently omit them.
   @Query("select c from Customer c where c.partyId is null "
        + "and (c.organizationId = :orgId or (c.organizationId is null and c.userId = :userId)) "
        + "order by c.name asc")
   List<Customer> findUnbridgedScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
