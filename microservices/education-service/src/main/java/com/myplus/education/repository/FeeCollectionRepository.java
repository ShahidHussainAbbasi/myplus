package com.myplus.education.repository;

import com.myplus.education.entity.FeeCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeeCollectionRepository extends JpaRepository<FeeCollection, Long> {
    Page<FeeCollection> findByUserId(Long userId, Pageable pageable);
    Page<FeeCollection> findByUserIdAndEn(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);
}
