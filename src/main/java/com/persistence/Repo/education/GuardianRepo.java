/**
 * 
 */
package com.persistence.Repo.education;

import java.util.Date;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Guardian;

/**
 * @author sabbasi
 *
 */
public interface GuardianRepo extends JpaRepository<Guardian, Long>,QueryByExampleExecutor<Guardian> {
	@Modifying
	@Query(value = "update guardian o set o.status = ? AND o.updated= ? where o.guardian_id = ?", nativeQuery = true)
	int updateStatus(String status, String id,Date updated);

}
