/**
 * 
 */
package com.myplus.business_service.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.stereotype.Repository;

import com.myplus.business_service.entity.Sell;

/**
 * @author sabbasi
 *
 */
@Repository
public interface SellRepo extends JpaRepository<Sell, Long>,QueryByExampleExecutor<Sell> {
	

    // Tenant-scoped read with NULL-fallback (own org's rows + caller's pre-migration org-NULL rows),
    // newest first. Replaces the Example-by-userId reads.
    @Query("select s from Sell s where s.organizationId = :orgId "
         + "or (s.organizationId is null and s.userId = :userId) order by s.sellId desc")
    List<Sell> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT s FROM Sell s WHERE s.dated >= :sd "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    public List<Sell> findSellByStartDate(@Param("sd") LocalDateTime sd, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT s FROM Sell s WHERE s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    public List<Sell> findSellByEndDate(@Param("ed") LocalDateTime ed, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // @EntityGraph(attributePaths = {"stock", "customerHistory", "customerHistory.customer"})
    @Query("SELECT s FROM Sell s WHERE s.updated >= :sd AND s.updated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    List<Sell> findSellByDates(
        @Param("sd") LocalDateTime sd,
        @Param("ed") LocalDateTime ed,
        @Param("orgId") Long orgId,
        @Param("userId") Long userId
    );
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
