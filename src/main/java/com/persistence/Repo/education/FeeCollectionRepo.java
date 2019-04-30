/**
 * 
 */
package com.persistence.Repo.education;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.FeeCollection;


/**
 * @author sabbasi
 *
 */
public interface FeeCollectionRepo extends JpaRepository<FeeCollection, Long>,QueryByExampleExecutor<FeeCollection> {
	
    @Query(value = "SELECT * FROM fee_collection fc WHERE fc.enroll_no =:enroll_no AND fc.payment_date >= :sd AND fc.user_Id=:userId",nativeQuery=true)
    public List<FeeCollection> findFCByStartDate(@Param("enroll_no") String enroll_no, @Param("sd") LocalDate sd,@Param("userId") Long userId);

    @Query(value = "SELECT * FROM fee_collection fc WHERE fc.enroll_no =:enroll_no AND fc.payment_date >= :ed AND fc.user_Id=:userId",nativeQuery=true)
    public List<FeeCollection> findFCByEndDate(@Param("enroll_no") String enroll_no, @Param("ed") LocalDate ed,@Param("userId") Long userId);

    @Query(value = "SELECT * FROM fee_collection fc WHERE fc.enroll_no =:enroll_no AND fc.payment_date >= :sd AND fc.payment_date <= :ed AND fc.user_Id=:userId",nativeQuery=true)
    public List<FeeCollection> findFCByDates(@Param("enroll_no") String enroll_no, @Param("sd") LocalDate sd,@Param("ed") LocalDate ed,@Param("userId") Long userId);

}
