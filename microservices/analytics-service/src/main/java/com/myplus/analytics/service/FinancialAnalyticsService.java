package com.myplus.analytics.service;

import com.myplus.analytics.dto.FinancialSummaryDTO;
import com.myplus.analytics.dto.MetricDTO;
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
public class FinancialAnalyticsService {

    private final AggregatedMetricRepository metricRepo;

    public FinancialSummaryDTO getFinancialSummary(LocalDate start, LocalDate end) {
        List<AggregatedMetric> revenue = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                "finance.revenue", AggregatedMetric.PeriodType.MONTHLY, start, end);
        List<AggregatedMetric> expenses = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                "finance.expenses", AggregatedMetric.PeriodType.MONTHLY, start, end);
        BigDecimal totalRev = revenue.stream()
                .map(m -> BigDecimal.valueOf(m.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExp = expenses.stream()
                .map(m -> BigDecimal.valueOf(m.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profit = totalRev.subtract(totalExp);
        double margin = totalRev.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(totalRev, 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;
        return FinancialSummaryDTO.builder()
                .totalRevenue(totalRev)
                .totalExpenses(totalExp)
                .profit(profit)
                .profitMargin(margin)
                .build();
    }

    public List<MetricDTO> getRevenueByPeriod(int months) {
        List<MetricDTO> out = new ArrayList<>();
        YearMonth current = YearMonth.now();
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            List<AggregatedMetric> metrics = metricRepo.findByMetricNameAndPeriodTypeAndPeriodStartBetween(
                    "finance.revenue", AggregatedMetric.PeriodType.MONTHLY, start, end);
            double sum = metrics.stream().mapToDouble(AggregatedMetric::getValue).sum();
            out.add(MetricDTO.builder()
                    .name("revenue")
                    .value(sum)
                    .period(ym.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                    .build());
        }
        return out;
    }
}
