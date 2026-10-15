package com.myplus.analytics.service;

import com.myplus.common.security.time.TenantClock;

import com.myplus.analytics.client.FinanceMetricsClient;
import com.myplus.analytics.dto.FinancialSummaryDTO;
import com.myplus.analytics.dto.FinancialTrendDTO;
import com.myplus.analytics.dto.MetricDTO;
import com.myplus.analytics.entity.AggregatedMetric;
import com.myplus.analytics.repository.AggregatedMetricRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AN-1 — the finance figures: finance's P&L month by month, stored per tenant as {@code finance.revenue} and
 * {@code finance.expenses} (MONTHLY) and served from the store.
 *
 * <p><b>Fresh on read.</b> Every read asks finance for the months it covers and upserts them first, so a back-dated
 * expense or a void is in the next read; there is no schedule or event that could be missed. finance computes each
 * month with the P&L's own method, so a month here is the P&L's month to the cent. If finance cannot be asked, the
 * months stored before are served and marked stale. If finance REFUSES the caller, nothing is served.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialAnalyticsService {

    static final String REVENUE = "finance.revenue", EXPENSES = "finance.expenses", SOURCE = "finance-service";
    static final int MAX_MONTHS = 24;

    private final AggregatedMetricRepository metricRepo;
    private final FinanceMetricsClient finance;

    /** The P&L month by month, {@code from}..{@code to} inclusive (default: the last 12 months). */
    public FinancialTrendDTO monthly(YearMonth from, YearMonth to) {
        YearMonth now = TenantClock.thisMonth();
        YearMonth t = to != null ? to : now;
        YearMonth f = from != null ? from : t.minusMonths(11);
        checkRange(f, t, now);
        Long org = CurrentUser.organizationId();
        if (org == null) throw new org.springframework.security.access.AccessDeniedException("no organisation");

        boolean stale = false;
        try {
            LocalDateTime at = LocalDateTime.now();
            for (FinanceMetricsClient.Month m : finance.monthly(f, t)) {
                metricRepo.upsert(org, REVENUE, "MONTHLY", m.from(), m.to(), money(m.totalIncome()), SOURCE, at);
                metricRepo.upsert(org, EXPENSES, "MONTHLY", m.from(), m.to(), money(m.totalExpense()), SOURCE, at);
            }
        } catch (FinanceMetricsClient.Unavailable e) {
            log.warn("AN-1: finance unavailable for org {} ({}); serving the stored months", org, e.getMessage());
            stale = true;
        }
        return fromStore(org, f, t, stale);
    }

    /**
     * Revenue, expenses and profit over the months that START between the two dates (whole months; this month up to
     * today), refreshed from finance as {@link #monthly} is.
     */
    public FinancialSummaryDTO getFinancialSummary(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) throw new ValidationException("The start date cannot be after the end date.");
        YearMonth now = TenantClock.thisMonth();
        YearMonth f = YearMonth.from(start.getDayOfMonth() == 1 ? start : start.plusMonths(1));
        YearMonth t = YearMonth.from(end).isAfter(now) ? now : YearMonth.from(end);
        BigDecimal rev = BigDecimal.ZERO, exp = BigDecimal.ZERO;
        boolean stale = false;
        if (!f.isAfter(t)) {
            FinancialTrendDTO trend = monthly(f, t);
            rev = trend.totalRevenue();
            exp = trend.totalExpenses();
            stale = trend.stale();
        }
        BigDecimal profit = rev.subtract(exp);
        double margin = rev.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(rev, 4, RoundingMode.HALF_UP).doubleValue() * 100
                : 0.0;
        return FinancialSummaryDTO.builder()
                .totalRevenue(rev)
                .totalExpenses(exp)
                .profit(profit)
                .profitMargin(margin)
                .stale(stale)
                .build();
    }

    public List<MetricDTO> getRevenueByPeriod(int months) {
        if (months < 1 || months > MAX_MONTHS) throw new ValidationException("At most " + MAX_MONTHS + " months at a time.");
        YearMonth now = TenantClock.thisMonth();
        List<MetricDTO> out = new ArrayList<>();
        for (FinancialTrendDTO.Month m : monthly(now.minusMonths(months - 1L), now).months()) {
            out.add(MetricDTO.builder()
                    .name("revenue")
                    .value(m.revenue() == null ? null : m.revenue().doubleValue())
                    .period(m.month())
                    .build());
        }
        return out;
    }

    /** The same rules finance applies, checked here too: with finance down, analytics still answers from its store. */
    static void checkRange(YearMonth f, YearMonth t, YearMonth now) {
        if (f.isAfter(t)) throw new ValidationException("The first month cannot be after the last month.");
        if (t.isAfter(now)) throw new ValidationException("The last month cannot be after this month.");
        if (ChronoUnit.MONTHS.between(f, t) + 1 > MAX_MONTHS) throw new ValidationException("At most " + MAX_MONTHS + " months at a time.");
    }

    private FinancialTrendDTO fromStore(Long org, YearMonth f, YearMonth t, boolean stale) {
        LocalDate a = f.atDay(1), b = t.atDay(1);
        Map<LocalDate, AggregatedMetric> rev = byStart(metricRepo.findOrgMetric(REVENUE, AggregatedMetric.PeriodType.MONTHLY, a, b, org));
        Map<LocalDate, AggregatedMetric> exp = byStart(metricRepo.findOrgMetric(EXPENSES, AggregatedMetric.PeriodType.MONTHLY, a, b, org));
        if (stale && rev.isEmpty() && exp.isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The books cannot be reached and nothing is stored yet.");
        List<FinancialTrendDTO.Month> months = new ArrayList<>();
        BigDecimal totalRev = BigDecimal.ZERO, totalExp = BigDecimal.ZERO;
        for (YearMonth m = f; !m.isAfter(t); m = m.plusMonths(1)) {
            AggregatedMetric r = rev.get(m.atDay(1)), e = exp.get(m.atDay(1));
            BigDecimal rv = r == null ? null : r.getValue(), ev = e == null ? null : e.getValue();
            if (rv != null) totalRev = totalRev.add(rv);
            if (ev != null) totalExp = totalExp.add(ev);
            LocalDate to = r != null ? r.getPeriodEnd() : e != null ? e.getPeriodEnd() : m.atEndOfMonth();
            LocalDateTime at = r != null ? r.getComputedAt() : e != null ? e.getComputedAt() : null;
            months.add(new FinancialTrendDTO.Month(m.toString(), m.atDay(1), to, rv, ev,
                    rv == null || ev == null ? null : rv.subtract(ev), at));
        }
        return new FinancialTrendDTO(months, totalRev, totalExp, totalRev.subtract(totalExp), stale);
    }

    private static Map<LocalDate, AggregatedMetric> byStart(List<AggregatedMetric> l) {
        Map<LocalDate, AggregatedMetric> m = new HashMap<>();
        for (AggregatedMetric x : l) m.put(x.getPeriodStart(), x);
        return m;
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
