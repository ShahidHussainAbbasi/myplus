/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Student;


/**
 * @author sabbasi
 *
 */
public interface StudentRepo extends JpaRepository<Student, Long>,QueryByExampleExecutor<Student> {
	@Modifying
	@Query(value = "update student o set o.status = ? where o.student_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
