package com.myplus.analytics.repository;

import com.myplus.analytics.entity.ReportDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinition, Long> {
    Page<ReportDefinition> findByCreatedByAndIsActiveTrue(Long createdBy, Pageable pageable);
    List<ReportDefinition> findByType(ReportDefinition.Type type);

    // Tenant scope: active-org rows + the caller's org-NULL legacy rows.
    String SCOPE = "(r.organizationId = :orgId OR (r.organizationId IS NULL AND r.createdBy = :userId))";

    @Query("select r from ReportDefinition r where " + SCOPE)
    Page<ReportDefinition> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    @Query("select r from ReportDefinition r where r.id = :id and " + SCOPE)
    Optional<ReportDefinition> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
