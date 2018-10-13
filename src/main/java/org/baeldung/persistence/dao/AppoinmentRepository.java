/**
 * 
 */
package org.baeldung.persistence.dao;

import java.util.Optional;

import org.baeldung.persistence.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

/**
 * @author sabbasi
 *
 */
public interface AppoinmentRepository extends JpaRepository<Appointment, Long>,QueryByExampleExecutor<Appointment> {
	

    @Query(value = "SELECT * FROM appointment t where t.FK_doctor_id = :doctor_id",nativeQuery=true)
    Appointment findByDoctor(Long doctor_id);
    
    @Query(value = "SELECT * FROM appointment t where t.FK_patient_id = :patient_id",nativeQuery=true)
    public Optional<Appointment> findByPatient(@Param("patient_id") Long patient_id);
}
