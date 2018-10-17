/**
 * 
 */
package com.persistence.dao;

import java.util.Optional;

import com.persistence.model.Doctor;
import com.persistence.model.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

/**
 * @author sabbasi
 *
 */
public interface DoctorRepository extends JpaRepository<Doctor, Long>,QueryByExampleExecutor<Doctor> {
	

    @Query(value = "SELECT * FROM Doctor d where d.email = :email",nativeQuery=true)
    Hospital findByEmail(String email);
    
    @Query(value = "SELECT * FROM Doctor d where d.mobile = :mobile",nativeQuery=true)
    public Optional<Hospital> findByMobile(@Param("mobile") String mobile);
}
