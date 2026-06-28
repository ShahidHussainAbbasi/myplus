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

   // M3c.4e (slice 87): the item's most recent purchase sell rate — the Stock-free source for carrying a legacy
   // item's price into the catalog at migration time (V6 backfilled bsellRate onto historical purchase rows).
   java.util.Optional<Purchase> findFirstByItemIdAndBsellRateNotNullOrderByPurchaseIdDesc(Long itemId);

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
