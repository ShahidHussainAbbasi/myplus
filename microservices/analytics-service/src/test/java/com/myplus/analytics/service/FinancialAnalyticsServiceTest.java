package com.myplus.analytics.service;

import com.myplus.analytics.client.FinanceMetricsClient;
import com.myplus.analytics.dto.FinancialTrendDTO;
import com.myplus.analytics.entity.AggregatedMetric;
import com.myplus.analytics.repository.AggregatedMetricRepository;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** AN-1 — fresh on read, stale said so, a refusal serves nothing, and the range rules. No Spring, no DB. */
class FinancialAnalyticsServiceTest {

    private final AggregatedMetricRepository repo = mock(AggregatedMetricRepository.class);
    private final FinanceMetricsClient finance = mock(FinanceMetricsClient.class);
    private final FinancialAnalyticsService svc = new FinancialAnalyticsService(repo, finance);
    private static final YearMonth SEP = YearMonth.of(2026, 9), OCT = YearMonth.of(2026, 10);

    @BeforeEach
    void setUp() {
        TenantClock.useClock(Clock.fixed(Instant.parse("2026-10-09T07:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(1L, "owner@test", List.of(), 7L), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); TenantClock.useClock(null); }

    private static AggregatedMetric stored(String name, YearMonth m, String value) {
        return AggregatedMetric.builder().metricName(name).periodType(AggregatedMetric.PeriodType.MONTHLY).organizationId(7L)
                .periodStart(m.atDay(1)).periodEnd(m.atEndOfMonth()).value(new BigDecimal(value))
                .computedAt(LocalDateTime.of(2026, 10, 1, 9, 0)).build();
    }

    @Test
    void freshOnRead_eachMonthIsUpsertedForTheCallersOrgThenServed() {
        when(finance.monthly(SEP, OCT)).thenReturn(List.of(
                new FinanceMetricsClient.Month("2026-09", SEP.atDay(1), SEP.atEndOfMonth(), new BigDecimal("500.1"), new BigDecimal("200.05"), null),
                new FinanceMetricsClient.Month("2026-10", OCT.atDay(1), LocalDate.of(2026, 10, 9), BigDecimal.ZERO, new BigDecimal("37.45"), null)));
        when(repo.findOrgMetric(eq("finance.revenue"), any(), any(), any(), eq(7L))).thenReturn(List.of(
                stored("finance.revenue", SEP, "500.10"), stored("finance.revenue", OCT, "0.00")));
        when(repo.findOrgMetric(eq("finance.expenses"), any(), any(), any(), eq(7L))).thenReturn(List.of(
                stored("finance.expenses", SEP, "200.05"), stored("finance.expenses", OCT, "37.45")));

        FinancialTrendDTO d = svc.monthly(SEP, OCT);

        verify(repo).upsert(eq(7L), eq("finance.revenue"), eq("MONTHLY"), eq(SEP.atDay(1)), eq(SEP.atEndOfMonth()),
                eq(new BigDecimal("500.10")), eq("finance-service"), any());
        verify(repo).upsert(eq(7L), eq("finance.expenses"), eq("MONTHLY"), eq(OCT.atDay(1)), eq(LocalDate.of(2026, 10, 9)),
                eq(new BigDecimal("37.45")), eq("finance-service"), any());
        assertFalse(d.stale());
        assertEquals(List.of("2026-09", "2026-10"), d.months().stream().map(FinancialTrendDTO.Month::month).toList());
        assertEquals(new BigDecimal("237.50"), d.totalExpenses());
        assertEquals(new BigDecimal("300.05"), d.months().get(0).net());
    }

    @Test
    void financeDown_servesTheStoredMonthsMarkedStale_aMonthNeverStoredIsNullNotZero() {
        when(finance.monthly(SEP, OCT)).thenThrow(new FinanceMetricsClient.Unavailable("down", null));
        when(repo.findOrgMetric(eq("finance.revenue"), any(), any(), any(), eq(7L))).thenReturn(List.of(stored("finance.revenue", SEP, "500.10")));
        when(repo.findOrgMetric(eq("finance.expenses"), any(), any(), any(), eq(7L))).thenReturn(List.of(stored("finance.expenses", SEP, "200.05")));

        FinancialTrendDTO d = svc.monthly(SEP, OCT);

        assertTrue(d.stale());
        verify(repo, never()).upsert(any(), anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertEquals(new BigDecimal("500.10"), d.months().get(0).revenue());
        assertNotNull(d.months().get(0).computedAt());
        assertNull(d.months().get(1).expenses(), "never stored: unknown, not zero");
    }

    @Test
    void financeDownAndNothingStored_is503() {
        when(finance.monthly(SEP, OCT)).thenThrow(new FinanceMetricsClient.Unavailable("down", null));
        when(repo.findOrgMetric(any(), any(), any(), any(), any())).thenReturn(List.of());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> svc.monthly(SEP, OCT)).getStatusCode().value());
    }

    @Test
    void financeRefusesTheCaller_nothingIsServedFromTheStore() {
        when(finance.monthly(SEP, OCT)).thenThrow(new AccessDeniedException("refused"));
        assertThrows(AccessDeniedException.class, () -> svc.monthly(SEP, OCT));
        verify(repo, never()).findOrgMetric(any(), any(), any(), any(), any());
    }

    @Test
    void rangeRules_checkedBeforeFinanceIsAsked() {
        assertTrue(assertThrows(ValidationException.class, () -> svc.monthly(OCT, SEP)).getMessage().contains("after"));
        assertTrue(assertThrows(ValidationException.class, () -> svc.monthly(YearMonth.of(2024, 4), OCT)).getMessage().contains("24 months"));
        assertThrows(ValidationException.class, () -> svc.monthly(OCT, YearMonth.of(2026, 11)));
        verifyNoInteractions(finance);
    }

    @Test
    void summaryCountsWholeMonthsStartingInTheRange() {
        when(finance.monthly(any(), any())).thenReturn(List.of());
        when(repo.findOrgMetric(any(), any(), any(), any(), any())).thenReturn(List.of(stored("finance.revenue", OCT, "10.00")));
        svc.getFinancialSummary(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 12, 31));
        verify(finance).monthly(SEP, OCT);   // August starts before the 15th; December is after this month
    }
}
