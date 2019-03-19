/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Alerts;

/**
 * @author sabbasi
 *
 */
public interface AlertsRepo extends JpaRepository<Alerts, Long>,QueryByExampleExecutor<Alerts> {
	
//	@Modifying
//	@Query(value = "update discount o set o.status = ? where o.discount_id = ?", nativeQuery = true)
//	int updateStatus(String status, String id);
}
