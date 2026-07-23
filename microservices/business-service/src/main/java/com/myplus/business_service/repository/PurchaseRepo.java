/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Purchase;

/**
 * @author sabbasi
 *
 */
public interface PurchaseRepo extends JpaRepository<Purchase, Long>,QueryByExampleExecutor<Purchase> {

   // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
   @Query("select p from purchase p where p.organizationId = :orgId "
        + "or (p.organizationId is null and p.userId = :userId)")
   List<Purchase> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // Multi-rate tax: input taxable + tax grouped by rate over [from,to], tenant-scoped, VOID bills excluded. Only
   // tax-bearing bills (taxRate set). NOTE partial purchase returns are separate debit notes and are NOT netted here
   // (the GL-sourced register remains the authoritative net-payable; this is an indicative per-rate split of activity).
   // Row = [rate, sum(totalAmount), sum(taxAmount)].
   @Query("select p.taxRate, sum(p.totalAmount), sum(p.taxAmount) from purchase p "
        + "where p.dated between :from and :to and p.taxRate is not null "
        + "and (p.status is null or p.status <> 'VOID') "
        + "and (p.organizationId = :orgId or (p.organizationId is null and p.userId = :userId)) "
        + "group by p.taxRate")
   List<Object[]> taxBreakdownByRate(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                     @Param("orgId") Long orgId, @Param("userId") Long userId);

   // OWN rows only (role-aware: a non-SUPER caller sees just the purchases they recorded).
   // Multi-location (P2b): store-aware variants — used only when the caller has store grants (non-empty set).
   @Query("select p from purchase p where p.organizationId = :orgId "
        + "and (p.storeId in :storeIds or p.storeId is null)")
   List<Purchase> findScopedByStores(@Param("orgId") Long orgId, @Param("storeIds") java.util.Collection<Long> storeIds);

   @Query("select p from purchase p where p.organizationId = :orgId and p.userId = :userId "
        + "and (p.storeId in :storeIds or p.storeId is null)")
   List<Purchase> findOwnScopedByStores(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                        @Param("storeIds") java.util.Collection<Long> storeIds);

   @Query("select p from purchase p where p.userId = :userId "
        + "and (p.organizationId = :orgId or p.organizationId is null)")
   List<Purchase> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // F1 (AP): a vendor's still-owing purchase bills (due_amount < 0), oldest first — for FIFO payment allocation.
   @Query("select p from purchase p where p.venderId = :venderId and p.dueAmount is not null and p.dueAmount < 0 "
        + "order by p.dated asc")
   List<Purchase> findOpenPurchasesByVendor(@Param("venderId") Long venderId);

   // F1 (AP): Σ(due) across a vendor's purchases — recomputePayable derives the vendor's running payable = −Σ(due).
   @Query("select coalesce(sum(p.dueAmount), 0) from purchase p where p.venderId = :venderId")
   java.math.BigDecimal sumDueByVendor(@Param("venderId") Long venderId);

   // F2 (AP aging): all still-owing vendor bills for the tenant (dueAmount < 0, a vendor set), org-scoped —
   // aging groups these by venderId. (venderId null = cash/legacy, excluded by the dueAmount<0 filter after seed.)
   @Query("select p from purchase p where p.dueAmount < 0 and p.venderId is not null and "
        + "(p.organizationId = :orgId or (p.organizationId is null and p.userId = :userId))")
   List<Purchase> findOpenBillsScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // F2 (AP statement): a vendor's bills oldest-first — the BILL lines of their statement of account.
   @Query("select p from purchase p where p.venderId = :venderId order by p.dated asc")
   List<Purchase> findByVenderOrdered(@Param("venderId") Long venderId);

   // SF-10: the product's most-recent purchase rate (unit cost / COGS) in this tenant — for the sell-line margin
   // snapshot. Returns newest-first; the caller takes the first with Pageable(0,1). NULL rates are skipped.
   @Query("select p.bpurchaseRate from purchase p where p.productId = :productId and p.bpurchaseRate is not null "
        + "and (p.organizationId = :orgId or (p.organizationId is null and p.userId = :userId)) order by p.dated desc")
   List<java.math.BigDecimal> findRecentCosts(@Param("productId") Long productId, @Param("orgId") Long orgId,
        @Param("userId") Long userId, org.springframework.data.domain.Pageable pageable);

   // M3c.4f (slice 88): the product_id backfill-from-stock queries were retired with the local Stock table.
   // The historical backfill ran at Flyway time (V5/V6) before the drop; nothing references local Stock anymore.


//    @Query(value = "SELECT * FROM appointment a,patient p WHERE a.FK_doctor_id = :doctor_id AND a.date = :date AND "
//    		+ "p.mobile = :mobile AND a.FK_patient_id = p.patient_id",nativeQuery=true)
//    Optional<Appointment> isPatientAppointed(@Param("doctor_id") Long doctor_id, @Param("date") String date, @Param("mobile") String mobile);
//    
//    @Query(value = "SELECT * FROM appointment t where t.FK_patient_id = :patient_id",nativeQuery=true)
//    public Optional<Appointment> findByPatient(@Param("patient_id") Long patient_id);
//
//    @Query(value = "SELECT * FROM appointment t where t.FK_doctor_id = :doctor_id",nativeQuery=true)
//    List<Appointment> findByDoctor(Long doctor_id);
//
//    @Query(value = "SELECT * FROM appointment a WHERE a.FK_hospital_id =:FK_hospital_id AND a.FK_doctor_id = :doctor_id AND a.date = :date"
//    		+" ORDER BY a.patients_appointed DESC LIMIT 1",nativeQuery=true)
//    Appointment getLastAppointment(Long FK_hospital_id, Long doctor_id, String date);
}
