package com.myplus.inventory.repository;

import com.myplus.inventory.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
    List<StockAlert> findByIsReadFalse();
    List<StockAlert> findByProductId(Long productId);

    @Modifying
    @Query("UPDATE StockAlert s SET s.isRead = true WHERE s.isRead = false")
    int markAllRead();
}
