package com.myplus.education.repository;

import com.myplus.education.entity.FeeCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeeCollectionRepository extends JpaRepository<FeeCollection, Long> {
    Page<FeeCollection> findByUserId(Long userId, Pageable pageable);
    List<FeeCollection> findByUserId(Long userId);
    Page<FeeCollection> findByUserIdAndEn(Long userId, String enrollNo, Pageable pageable);
    long countByUserId(Long userId);
}
