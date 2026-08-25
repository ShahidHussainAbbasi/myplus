/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Company;

/**
 * @author sabbasi
 *
 */
public interface CompanyRepo extends JpaRepository<Company, Long>,QueryByExampleExecutor<Company> {

   // Tenant-scoped read with NULL-fallback (own org + caller's pre-migration org-NULL rows).
   @Query("select c from Company c where c.organizationId = :orgId "
        + "or (c.organizationId is null and c.userId = :userId)")
   List<Company> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

   /**
    * How many, WITHOUT loading them.
    *
    * <p>The dashboard used to call {@code findScoped(...).size()} — which hydrates every row of the tenant's
    * table into JPA entities and then throws them away to keep an integer. On the customer table that is the
    * same read that returns ~196KB elsewhere, and it is why the stats endpoint answered in ~640ms for a
    * 183-byte payload.
    *
    * <p><b>The predicate is a character-for-character copy of {@link #findScoped}</b>, including the NULL-org
    * fallback, and that is the whole risk of this change: a COUNT that scopes even slightly differently
    * returns a plausible number that is quietly wrong, and no screen would reveal it. The gate asserts the
    * count equals the list size for the same caller.
    */
   @Query("select count(c) from Company c where c.organizationId = :orgId "
        + "or (c.organizationId is null and c.userId = :userId)")
   long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);


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
