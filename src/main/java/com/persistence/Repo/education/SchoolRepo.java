/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.persistence.model.education.School;

/**
 * @author sabbasi
 *
 */
public interface SchoolRepo extends JpaRepository<School, Long> {
	@Modifying
	@Query(value = "update school o set o.status = ? where o.school_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
