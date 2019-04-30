/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.AlertChannel;

/**
 * @author sabbasi
 *
 */
public interface AlertChannelRepo extends JpaRepository<AlertChannel, Long>,QueryByExampleExecutor<AlertChannel> {
	
//	@Modifying
//	@Query(value = "update discount o set o.status = ? where o.discount_id = ?", nativeQuery = true)
//	int updateStatus(String status, String id);
}
