/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.persistence.model.education.Student;


/**
 * @author sabbasi
 *
 */

public interface DashboardRepo extends JpaRepository<Student, Long>{
	
	@Query(value = "SELECT count(*) as 'freshStudent',(SELECT count(*) FROM student WHERE user_id =:userId) as 'allStudent' FROM student WHERE enroll_date >= :lastMonth AND user_id =:userId", nativeQuery = true)
	public Object getDashboardData(String lastMonth,Long userId);
}
