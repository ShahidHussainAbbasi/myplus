package com.myplus.finance.service;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.finance.entity.Account;
import com.myplus.finance.entity.AccountType;
import com.myplus.finance.repository.AccountRepository;
import com.myplus.finance.repository.JournalEntryRepository;
import com.myplus.finance.repository.JournalLineRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** AN-1 — the P&L month by month: each month is the P&L over that month (this month up to today), and the range rules. */
class GlMonthlyPnlTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final JournalLineRepository lines = mock(JournalLineRepository.class);
    private GlService gl;

    @BeforeEach
    void setUp() {
        TenantClock.useClock(Clock.fixed(Instant.parse("2026-10-09T07:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(1L, "owner@test", List.of(), 7L), null, List.of()));
        gl = new GlService(accounts, mock(JournalEntryRepository.class), lines, mock(PeriodLockService.class));
        when(accounts.findByOrganizationIdOrderByCodeAsc(7L)).thenReturn(List.of(
                Account.builder().id(1L).code("4000").name("Sales").type(AccountType.INCOME).organizationId(7L).build(),
                Account.builder().id(2L).code("6000").name("Rent").type(AccountType.EXPENSE).organizationId(7L).build()));
        when(lines.sumByAccountInRange(eq(7L), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); TenantClock.useClock(null); }

    private static List<Object[]> rows(String income, String expense) {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{1L, BigDecimal.ZERO, new BigDecimal(income)});
        l.add(new Object[]{2L, new BigDecimal(expense), BigDecimal.ZERO});
        return l;
    }

    @Test
    void eachMonthIsThePnlOverThatMonth_thisMonthUpToToday() {
        when(lines.sumByAccountInRange(7L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(rows("500.10", "200.05"));
        when(lines.sumByAccountInRange(7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))).thenReturn(rows("90", "37.45"));

        List<Map<String, Object>> out = gl.monthlyProfitAndLoss(YearMonth.of(2026, 8), YearMonth.of(2026, 10));

        assertEquals(List.of("2026-08", "2026-09", "2026-10"), out.stream().map(m -> m.get("month")).toList());
        assertEquals(LocalDate.of(2026, 8, 31), out.get(0).get("to"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get(0).get("totalExpense")), "a quiet month is zero, not missing");
        assertEquals(new BigDecimal("500.10"), out.get(1).get("totalIncome"));
        assertEquals(new BigDecimal("300.05"), out.get(1).get("netProfit"));
        assertEquals(LocalDate.of(2026, 10, 9), out.get(2).get("to"), "this month stops at today");
        assertEquals(new BigDecimal("37.45"), out.get(2).get("totalExpense"));
    }

    @Test
    void defaultsToTheLastTwelveMonths() {
        List<Map<String, Object>> out = gl.monthlyProfitAndLoss(null, null);
        assertEquals(12, out.size());
        assertEquals("2025-11", out.get(0).get("month"));
        assertEquals("2026-10", out.get(11).get("month"));
    }

    @Test
    void rangeRules() {
        assertTrue(assertThrows(ValidationException.class,
                () -> gl.monthlyProfitAndLoss(YearMonth.of(2026, 10), YearMonth.of(2026, 8))).getMessage().contains("after"));
        assertTrue(assertThrows(ValidationException.class,
                () -> gl.monthlyProfitAndLoss(YearMonth.of(2024, 4), YearMonth.of(2026, 10))).getMessage().contains("24 months"));
        assertThrows(ValidationException.class, () -> gl.monthlyProfitAndLoss(YearMonth.of(2026, 10), YearMonth.of(2026, 11)));
        assertEquals(24, gl.monthlyProfitAndLoss(YearMonth.of(2024, 11), YearMonth.of(2026, 10)).size());
    }
}
