/**
 * 
 */
package com.persistence.Repo.business;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.business.Sell;

/**
 * @author sabbasi
 *
 */
public interface SellRepo extends JpaRepository<Sell, Long>,QueryByExampleExecutor<Sell> {
	

    @Query(value = "SELECT * FROM sell s where s.dated >= :sd AND s.user_Id=:userId",nativeQuery=true)
    public List<Sell> findSellByStartDate(@Param("sd") LocalDateTime sd,@Param("userId") Long userId);

    @Query(value = "SELECT * FROM sell s where s.dated <= :ed AND s.user_Id=:userId",nativeQuery=true)
    public List<Sell> findSellByEndDate(@Param("ed") LocalDateTime ed,@Param("userId") Long userId);

    @Query(value = "SELECT * FROM sell s where s.dated >= :sd AND s.dated <= :ed AND s.user_Id=:userId",nativeQuery=true)
    public List<Sell> findSellByDates(@Param("sd") LocalDateTime sd,@Param("ed") LocalDateTime ed,@Param("userId") Long userId);


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
