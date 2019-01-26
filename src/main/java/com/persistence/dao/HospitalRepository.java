///**
// * 
// */
//package com.persistence.dao;
//
//import java.util.Optional;
//
//import com.persistence.model.Hospital;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//import org.springframework.data.repository.query.QueryByExampleExecutor;
//
///**
// * @author sabbasi
// *
// */
//public interface HospitalRepository extends JpaRepository<Hospital, Long>,QueryByExampleExecutor<Hospital> {
//	
//
//    Hospital findByEmail(String email);
//    
//    @Query(
//            value = "SELECT * FROM hospital h where h.name = :name", 
//            nativeQuery=true
//        )
//        public Optional<Hospital> findByName(@Param("name") String name);
////    Hospital findByName(String name);
//    @Override
//    void delete(Hospital hospital);
//}
