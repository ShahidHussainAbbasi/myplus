/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Discount;

/**
 * @author sabbasi
 *
 */
public interface DiscountRepo extends JpaRepository<Discount, Long>,QueryByExampleExecutor<Discount> {
	
	@Modifying
	@Query(value = "update discount o set o.status = ? where o.discount_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
