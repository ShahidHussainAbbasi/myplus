package com.myplus.analytics.repository;

import com.myplus.analytics.entity.DashboardWidget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DashboardWidgetRepository extends JpaRepository<DashboardWidget, Long> {
    List<DashboardWidget> findByUserIdAndIsActiveTrueOrderByPosition(Long userId);
}
