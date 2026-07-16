package com.myplus.analytics.repository;

import com.myplus.analytics.entity.AggregatedMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AggregatedMetricRepository extends JpaRepository<AggregatedMetric, Long> {
    List<AggregatedMetric> findByMetricNameAndPeriodType(String metricName, AggregatedMetric.PeriodType periodType);
    List<AggregatedMetric> findByServiceSourceAndPeriodTypeAndPeriodStartBetween(
            String serviceSource, AggregatedMetric.PeriodType periodType, LocalDate from, LocalDate to);
    List<AggregatedMetric> findByMetricNameAndPeriodTypeAndPeriodStartBetween(
            String metricName, AggregatedMetric.PeriodType periodType, LocalDate from, LocalDate to);

    // Tenant-scoped metric read: the caller's org, plus any legacy pre-migration metric (organization_id NULL,
    // visible to all until recomputed with an org). A metric is system-computed and has no creator user, so
    // there is no created_by fallback — the boundary is org-or-legacy. The unscoped variant above returned
    // every tenant's numbers.
    @Query("select m from AggregatedMetric m where m.metricName = :name and m.periodType = :type "
            + "and m.periodStart between :from and :to "
            + "and (m.organizationId = :orgId OR m.organizationId IS NULL)")
    List<AggregatedMetric> findScopedByName(@Param("name") String name,
            @Param("type") AggregatedMetric.PeriodType type,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("orgId") Long orgId);

    // No-date scoped variant (used by the all-time roll-ups). Same org-or-legacy boundary.
    @Query("select m from AggregatedMetric m where m.metricName = :name and m.periodType = :type "
            + "and (m.organizationId = :orgId OR m.organizationId IS NULL)")
    List<AggregatedMetric> findScopedByNameAllPeriods(@Param("name") String name,
            @Param("type") AggregatedMetric.PeriodType type, @Param("orgId") Long orgId);
}
