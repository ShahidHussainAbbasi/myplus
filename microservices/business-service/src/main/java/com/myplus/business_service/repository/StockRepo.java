/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.stereotype.Repository;

import com.myplus.business_service.entity.Stock;

/**
 * @author sabbasi
 *
 */
@Repository
public interface StockRepo extends JpaRepository<Stock, Long>,QueryByExampleExecutor<Stock> {
	
//	@Query(value = "SELECT batch_no FROM stock WHERE user_id = :userId AND item_id = :itemId",nativeQuery=true)
//	Set<String> getItemBatch(Long userId, Long itemId);

	@Query(value = "SELECT batchNo FROM Stock WHERE userId = :userId AND itemId = :itemId")
	Set<String> getItemBatch(Long userId, Long itemId);

	// Tenant-scoped variants (own org + caller's pre-migration org-NULL rows).
	@Query("SELECT s.batchNo FROM Stock s WHERE s.itemId = :itemId "
	     + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
	Set<String> getItemBatchScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, @Param("itemId") Long itemId);

	@Query("SELECT s FROM Stock s WHERE s.batchNo = :batchNo "
	     + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
	Optional<Stock> findByBatchScoped(@Param("batchNo") String batchNo, @Param("orgId") Long orgId, @Param("userId") Long userId);

	Optional<Stock> findByItemId(Long itemId);

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
