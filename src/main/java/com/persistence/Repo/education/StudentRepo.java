/**
 * 
 */
package com.persistence.Repo.education;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Student;


/**
 * @author sabbasi
 *
 */
public interface StudentRepo extends JpaRepository<Student, Long>,QueryByExampleExecutor<Student> {
	@Modifying
	@Query(value = "update student s set s.status = ? where s.student_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);

//	@Query(value="select * from appointment t where t.hospital in :hospital",nativeQuery=true)
//	List<Appointment> findByHospitalIds(@Param("hospital") List<Hospital> hospital);
//
//	@Query(value = "Select * from student s where s.user_id = :userId and s.student_id in :studentIds AND s.status =:status", nativeQuery = true)
//	List<Student> findStudentsByStudentIdsAndUserId(@Param("userId") Long userId,@Param("studentIds") List<Long> studentIds,@Param("status") String status);

	@Query(value = "Select * from student s where s.user_id = :userId and s.guardian_id in :guardianIds AND s.status =:status", nativeQuery = true)
	List<Student> findStudentsByGuardianIdsAndUserId(@Param("userId") Long userId,@Param("guardianIds") List<Long> guardianIds,@Param("status") String status);

	@Query(value = "Select * from student s where s.user_id = :userId and s.grade_id in :gradeIds AND s.status =:status", nativeQuery = true)
	List<Student> findStudentsByGradeIdsAndUserId(@Param("userId") Long userId,@Param("gradeIds") List<Long> gradeIds,@Param("status") String status);

	@Query(value = "Select * from student s where s.user_id = :userId and s.student_id in :studentIds AND s.status =:status", nativeQuery = true)
	List<Student> findStudentsByStudentIdsAndUserId(Long userId, List<Long> studentIds, String status);

	@Query(value = "Select * from student s where s.user_id = :userId and s.student_id in :campusIds AND s.status =:status", nativeQuery = true)
	List<Student> findStudentsByCampusIdsAndUserId(Long userId, List<Long> campusIds, String status);

}
