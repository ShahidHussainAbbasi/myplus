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

    /**
     * SRA-4a — set INSTEAD of a label when the group is a code, not a name: a sale source (COUNTER, REP_LOGIN …) or a
     * gap (NOT_RECORDED, NO_REP, NO_AREA, NO_SUPPLIER). The screen shows its bundle key, so the words are translated and
     * a rep who happens to be called "Counter" can never be confused with the counter.
     */
    private String labelCode;

    /** SRA-4a — free goods: quantity, and value at TP (the SSR convention). Zero on a report with no bonus. */
    private BigDecimal bonusQuantity = BigDecimal.ZERO;
    private BigDecimal bonusValue = BigDecimal.ZERO;

    public SaleReportGroup(String label, int invoices, BigDecimal quantity, BigDecimal total, BigDecimal tax) {
        this.label = label;
        this.invoices = invoices;
        this.quantity = quantity;
        this.total = total;
        this.tax = tax;
    }

    /** total + tax — what the customer actually paid for this group. */
    public BigDecimal getGross() {
        return nz(total).add(nz(tax));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
