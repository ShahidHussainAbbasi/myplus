package com.myplus.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AN-1 — the P&L month by month. {@code stale} = finance could not be asked, so these are the months as last stored
 * (each with its {@code computedAt}); a month never stored then has null figures, never an invented zero.
 */
public record FinancialTrendDTO(List<Month> months, BigDecimal totalRevenue, BigDecimal totalExpenses,
                                BigDecimal net, boolean stale) {

    public record Month(String month, LocalDate from, LocalDate to, BigDecimal revenue, BigDecimal expenses,
                        BigDecimal net, LocalDateTime computedAt) {}
}
