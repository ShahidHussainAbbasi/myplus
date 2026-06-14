package com.myplus.analytics.repository;

import com.myplus.analytics.entity.AggregatedMetric;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
