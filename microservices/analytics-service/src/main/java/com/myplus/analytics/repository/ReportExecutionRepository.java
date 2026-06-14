package com.myplus.analytics.repository;

import com.myplus.analytics.entity.ReportExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReportExecutionRepository extends JpaRepository<ReportExecution, Long> {
    Page<ReportExecution> findByReportId(Long reportId, Pageable pageable);
    Page<ReportExecution> findByReportIdAndStatus(Long reportId, ReportExecution.Status status, Pageable pageable);
    Optional<ReportExecution> findTopByReportIdOrderByStartedAtDesc(Long reportId);
}
