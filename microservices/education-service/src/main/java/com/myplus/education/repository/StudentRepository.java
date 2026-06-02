package com.myplus.education.repository;

import com.myplus.education.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Page<Student> findByUserId(Long userId, Pageable pageable);
    Page<Student> findBySchoolId(Long schoolId, Pageable pageable);
    Page<Student> findByGradeId(Long gradeId, Pageable pageable);
    long countByUserId(Long userId);
}
