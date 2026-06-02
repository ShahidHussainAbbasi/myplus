package com.myplus.analytics.service;

import com.myplus.analytics.entity.AggregatedMetric;
import com.myplus.analytics.repository.AggregatedMetricRepository;
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
        return metricRepo.save(metric);
    }

    @Transactional(readOnly = true)
    public List<AggregatedMetric> getMetrics(String metricName, String periodType, LocalDate start, LocalDate end) {
        AggregatedMetric.PeriodType type = AggregatedMetric.PeriodType.valueOf(periodType.toUpperCase());
        return metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(metricName, type, start, end);
    }
}
