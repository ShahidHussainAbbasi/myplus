/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Subject;

/**
 * @author sabbasi
 *
 */
public interface SubjectRepo extends JpaRepository<Subject, Long>,QueryByExampleExecutor<Subject> {
	@Modifying
	@Query(value = "update subject o set o.status = ? where o.subject_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
