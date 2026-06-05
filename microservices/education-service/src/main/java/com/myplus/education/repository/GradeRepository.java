package com.myplus.education.repository;

import com.myplus.education.entity.Grade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {
    Page<Grade> findByUserId(Long userId, Pageable pageable);
    List<Grade> findByUserId(Long userId);
    List<Grade> findBySchoolId(Long schoolId);
    long countByUserId(Long userId);
}
