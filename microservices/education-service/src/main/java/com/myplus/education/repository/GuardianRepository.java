package com.myplus.education.repository;

import com.myplus.education.entity.Guardian;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuardianRepository extends JpaRepository<Guardian, Long> {
    Page<Guardian> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
}
