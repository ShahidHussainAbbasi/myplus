package com.myplus.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialSummaryDTO {
    private BigDecimal totalRevenue;
    private BigDecimal totalExpenses;
    private BigDecimal profit;
    private double profitMargin;
    /** AN-1 — true when finance could not be asked and these are the months as last stored. */
    private boolean stale;
}
