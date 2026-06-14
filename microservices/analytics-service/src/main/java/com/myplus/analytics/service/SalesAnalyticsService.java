package com.myplus.analytics.service;

import com.myplus.analytics.dto.MetricDTO;
import com.myplus.analytics.dto.SalesAnalyticsDTO;
import com.myplus.analytics.entity.AggregatedMetric;
import com.myplus.analytics.repository.AggregatedMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesAnalyticsService {

    private final AggregatedMetricRepository metricRepo;

    public List<SalesAnalyticsDTO> getSalesTrend(int months) {
        List<SalesAnalyticsDTO> result = new ArrayList<>();
        YearMonth current = YearMonth.now();
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            List<AggregatedMetric> revMetrics = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                    "sales.revenue", AggregatedMetric.PeriodType.MONTHLY, start, end);
            BigDecimal revenue = revMetrics.stream()
                    .map(m -> BigDecimal.valueOf(m.getValue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<AggregatedMetric> countMetrics = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                    "sales.count", AggregatedMetric.PeriodType.MONTHLY, start, end);
            int salesCount = countMetrics.stream()
                    .mapToInt(m -> m.getValue().intValue())
                    .sum();
            BigDecimal avg = salesCount > 0
                    ? revenue.divide(BigDecimal.valueOf(salesCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            result.add(SalesAnalyticsDTO.builder()
                    .month(ym.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                    .revenue(revenue)
                    .salesCount(salesCount)
                    .avgOrderValue(avg)
                    .build());
        }
        return result;
    }

    public List<MetricDTO> getDailySales(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<AggregatedMetric> metrics = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                "sales.revenue", AggregatedMetric.PeriodType.DAILY, start, end);
        List<MetricDTO> result = new ArrayList<>();
        for (AggregatedMetric m : metrics) {
            result.add(MetricDTO.builder()
                    .name(m.getMetricName())
                    .value(m.getValue())
                    .period(m.getPeriodStart().toString())
                    .dimension(m.getDimension())
                    .build());
        }
        return result;
    }

    public List<MetricDTO> getTopMetrics() {
        List<AggregatedMetric> rev = metricRepo.findByMetricNameAndPeriodType("sales.revenue", AggregatedMetric.PeriodType.MONTHLY);
        List<AggregatedMetric> cnt = metricRepo.findByMetricNameAndPeriodType("sales.count", AggregatedMetric.PeriodType.MONTHLY);
        double totalRevenue = rev.stream().mapToDouble(AggregatedMetric::getValue).sum();
        double totalCount = cnt.stream().mapToDouble(AggregatedMetric::getValue).sum();
        List<MetricDTO> out = new ArrayList<>();
        out.add(MetricDTO.builder().name("totalRevenue").value(totalRevenue).period("all-time").build());
        out.add(MetricDTO.builder().name("totalSalesCount").value(totalCount).period("all-time").build());
        out.add(MetricDTO.builder().name("avgOrderValue")
                .value(totalCount > 0 ? totalRevenue / totalCount : 0.0).period("all-time").build());
        return out;
    }
}
