package com.myplus.analytics.repository;

import com.myplus.analytics.entity.ReportDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinition, Long> {
    Page<ReportDefinition> findByCreatedByAndIsActiveTrue(Long createdBy, Pageable pageable);
    List<ReportDefinition> findByType(ReportDefinition.Type type);
}
