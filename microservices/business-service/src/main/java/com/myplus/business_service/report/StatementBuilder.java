package com.myplus.business_service.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * F2: pure, party-agnostic statement builder — orders bill/payment lines by date and fills the running balance
 * (opening + Σdebit − Σcredit). Unit-testable. Used by both AR (customer) and AP (vendor) statements.
 */
public final class StatementBuilder {

    private StatementBuilder() {}

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** Sort {@code lines} by date (nulls first) and set each line's running balance from {@code opening}.
     *  Mutates and returns the same list. The final line's balance is the closing balance. */
    public static List<StatementLine> build(List<StatementLine> lines, BigDecimal opening) {
        if (lines == null) return java.util.Collections.emptyList();
        lines.sort(Comparator.comparing(l -> l.getDate() != null ? l.getDate() : LocalDate.MIN));
        BigDecimal bal = nz(opening);
        for (StatementLine l : lines) {
            bal = bal.add(nz(l.getDebit())).subtract(nz(l.getCredit()));
            l.setBalance(bal);
        }
        return lines;
    }
}
