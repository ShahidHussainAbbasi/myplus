package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One aggregated line of the sale report (slice b2b-P3e-2 = requirement #6) — "this customer / day /
 * category, and what it came to".
 *
 * <p>Money is {@link BigDecimal} throughout. A report a shop reconciles its takings against must not drift
 * by a rounding error, which is exactly what summing doubles would do over a month of lines.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleReportGroup {

    /** The group's display label, e.g. "2026-08-04", "Acme Ltd", "Drinks". */
    private String label;

    /** Distinct invoices in this group — a shop counts transactions, not just lines. */
    private int invoices;

    /** Units sold. */
    private BigDecimal quantity;

    /** Line totals before tax. */
    private BigDecimal total;

    /** Tax on those lines. */
    private BigDecimal tax;

    /** total + tax — what the customer actually paid for this group. */
    public BigDecimal getGross() {
        return nz(total).add(nz(tax));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
