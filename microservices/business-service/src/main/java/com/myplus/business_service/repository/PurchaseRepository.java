package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Purchase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    Page<Purchase> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT p FROM purchase p WHERE p.userId = :userId AND p.dated BETWEEN :start AND :end")
    List<Purchase> findByUserAndDateRange(@Param("userId") Long userId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(p.netAmount), 0.0) FROM purchase p WHERE p.userId = :userId AND p.dated BETWEEN :start AND :end")
    Double sumByUserAndDateRange(@Param("userId") Long userId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
