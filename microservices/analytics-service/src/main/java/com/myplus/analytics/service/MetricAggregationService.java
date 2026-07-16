package com.myplus.analytics.service;

import com.myplus.analytics.entity.AggregatedMetric;
import com.myplus.analytics.repository.AggregatedMetricRepository;
import com.myplus.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MetricAggregationService {

    private final AggregatedMetricRepository metricRepo;

    public AggregatedMetric saveMetric(AggregatedMetric metric) {
        if (metric.getOrganizationId() == null) metric.setOrganizationId(CurrentUser.organizationId());
        return metricRepo.save(metric);
    }

    @Transactional(readOnly = true)
    public List<AggregatedMetric> getMetrics(String metricName, String periodType, LocalDate start, LocalDate end) {
        AggregatedMetric.PeriodType type = AggregatedMetric.PeriodType.valueOf(periodType.toUpperCase());
        // Tenant-scoped: the caller's org (+ legacy org-NULL). The unscoped query returned every tenant's metrics.
        return metricRepo.findScopedByName(metricName, type, start, end, CurrentUser.organizationId());
    }
}
