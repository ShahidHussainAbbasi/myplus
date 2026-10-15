package com.myplus.analytics.repository;

import com.myplus.analytics.entity.AggregatedMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * AN-1 — the caller's org ONLY. A produced metric (finance.*) is always stamped with its org, so the legacy
     * org-NULL fallback above would only ever add a row that is not this tenant's into this tenant's total.
     */
    @Query("select m from AggregatedMetric m where m.metricName = :name and m.periodType = :type "
            + "and m.periodStart between :from and :to and m.organizationId = :orgId order by m.periodStart")
    List<AggregatedMetric> findOrgMetric(@Param("name") String name, @Param("type") AggregatedMetric.PeriodType type,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("orgId") Long orgId);

    /**
     * AN-1 — one row per (org, metric, period type, period start), written in one statement on the V3 unique key:
     * two readers refreshing the same month at once leave one row, never two.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO aggregated_metrics (organization_id, metric_name, period_type, period_start, period_end, "
            + "`value`, service_source, computed_at) VALUES (:orgId, :name, :type, :start, :end, :value, :source, :at) "
            + "ON DUPLICATE KEY UPDATE period_end = VALUES(period_end), `value` = VALUES(`value`), "
            + "service_source = VALUES(service_source), computed_at = VALUES(computed_at)", nativeQuery = true)
    int upsert(@Param("orgId") Long orgId, @Param("name") String name, @Param("type") String periodType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, @Param("value") BigDecimal value,
            @Param("source") String source, @Param("at") LocalDateTime at);
}
