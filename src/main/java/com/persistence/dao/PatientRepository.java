///**
// * 
// */
//package com.persistence.dao;
//
//import java.util.Optional;
//
//import com.persistence.model.Patient;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//import org.springframework.data.repository.query.QueryByExampleExecutor;
//
///**
// * @author sabbasi
// *
// */
//public interface PatientRepository extends JpaRepository<Patient, Long>,QueryByExampleExecutor<Patient> {
//	    
//    @Query(value = "SELECT * FROM patient t where t.cnic = :cnic",nativeQuery=true)
//    public Optional<Patient> findByCNIC(@Param("cnic") String cnic);
//
//    @Query(value = "SELECT * FROM patient t where t.mobile = :mobile",nativeQuery=true)
//    public Patient findByPhone(String mobile);
//}
