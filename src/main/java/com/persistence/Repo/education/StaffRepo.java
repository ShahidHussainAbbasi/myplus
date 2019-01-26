/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Staff;

/**
 * @author sabbasi
 *
 */
//@Repository
public interface StaffRepo extends JpaRepository<Staff, Long>,QueryByExampleExecutor<Staff> {
	@Modifying
	@Query(value = "update staff o set o.status = ? where o.staff_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
