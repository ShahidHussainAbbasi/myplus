/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Grade;

/**
 * @author sabbasi
 *
 */
public interface GradeRepo extends JpaRepository<Grade, Long>,QueryByExampleExecutor<Grade> {
	
	@Modifying
	@Query(value = "update grade o set o.status = ? where o.grade_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);

}
