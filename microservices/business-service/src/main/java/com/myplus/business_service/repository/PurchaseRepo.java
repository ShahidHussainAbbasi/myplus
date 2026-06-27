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

   // OWN rows only (role-aware: a non-SUPER caller sees just the purchases they recorded).
   @Query("select p from purchase p where p.userId = :userId "
        + "and (p.organizationId = :orgId or p.organizationId is null)")
   List<Purchase> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   // M3c.1 (slice 76): backfill product_id onto historical (Stock-linked) purchases from the item→product map, so
   // the Stock FK can later be retired. Idempotent (only NULL product_id rows); tenant-scoped (NULL-fallback).
   @Modifying(clearAutomatically = true, flushAutomatically = true)
   @Query(value = "UPDATE purchase p JOIN stock st ON p.stock_id = st.stock_id "
        + "JOIN item_catalog_map m ON m.item_id = st.item_id "
        + "SET p.product_id = m.product_id "
        + "WHERE p.product_id IS NULL AND p.stock_id IS NOT NULL "
        + "AND (p.organization_id = :orgId OR (p.organization_id IS NULL AND p.user_id = :userId))", nativeQuery = true)
   int backfillProductIds(@Param("orgId") Long orgId, @Param("userId") Long userId);

   @Query(value = "SELECT COUNT(*) FROM purchase p WHERE p.product_id IS NULL AND p.stock_id IS NOT NULL "
        + "AND (p.organization_id = :orgId OR (p.organization_id IS NULL AND p.user_id = :userId))", nativeQuery = true)
   long countWithoutProductId(@Param("orgId") Long orgId, @Param("userId") Long userId);


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
