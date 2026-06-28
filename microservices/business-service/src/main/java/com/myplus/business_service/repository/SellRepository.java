package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Sell;
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
public interface SellRepository extends JpaRepository<Sell, Long> {
    Page<Sell> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT s FROM Sell s WHERE s.userId = :userId AND s.dated BETWEEN :start AND :end")
    List<Sell> findByUserAndDateRange(@Param("userId") Long userId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(s.netAmount), 0.0) FROM Sell s WHERE s.userId = :userId AND s.dated BETWEEN :start AND :end")
    Double sumByUserAndDateRange(@Param("userId") Long userId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT MONTH(s.dated), COALESCE(SUM(s.netAmount), 0.0) FROM Sell s WHERE s.userId = :userId AND YEAR(s.dated) = :year GROUP BY MONTH(s.dated)")
    List<Object[]> monthlySales(@Param("userId") Long userId, @Param("year") int year);

    @Query("SELECT s.productId, COALESCE(SUM(s.quantity), 0) FROM Sell s WHERE s.userId = :userId AND s.productId IS NOT NULL GROUP BY s.productId ORDER BY COALESCE(SUM(s.quantity), 0) DESC")
    List<Object[]> topSellingItems(@Param("userId") Long userId, Pageable pageable);
}
