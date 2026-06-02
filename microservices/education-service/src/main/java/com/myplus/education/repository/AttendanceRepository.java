package com.myplus.education.repository;

import com.myplus.education.entity.Attendance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    Page<Attendance> findByUserId(Long userId, Pageable pageable);
    Page<Attendance> findByUserIdAndEn(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);
}
