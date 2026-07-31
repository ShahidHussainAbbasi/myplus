package com.myplus.common.subledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * F2: pure, party-agnostic aging — buckets outstanding amounts by age (0–30 / 31–60 / 61–90 / 90+). Used by BOTH
 * AR (customers) and AP (vendors): the caller supplies rows of {outstanding, ageDate}; age basis is the due date
 * (falling back to the document date) per the F2 decision. Unit-testable (no Spring, no I/O).
 */
public final class AgingCalculator {

    private AgingCalculator() {}

    /** One still-owing amount + the date its age is measured from (due date, or doc date if no due date). */
    public record AgingRow(BigDecimal outstanding, LocalDate ageDate) {}

    /** Bucket sums as {@code [0–30, 31–60, 61–90, 90+]}. Rows with a non-positive outstanding are skipped;
     *  future-dated (age < 0) counts as current (0–30). */
    public static BigDecimal[] bucketize(List<AgingRow> rows, LocalDate asOf) {
        BigDecimal[] b = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        if (rows == null) return b;
        for (AgingRow r : rows) {
            if (r == null || r.outstanding() == null || r.outstanding().signum() <= 0) continue;
            LocalDate from = r.ageDate() != null ? r.ageDate() : asOf;
            long age = ChronoUnit.DAYS.between(from, asOf);
            int idx = age <= 30 ? 0 : age <= 60 ? 1 : age <= 90 ? 2 : 3;
            b[idx] = b[idx].add(r.outstanding());
        }
        return b;
    }

    /** Sum of a bucket array. */
    public static BigDecimal total(BigDecimal[] buckets) {
        BigDecimal t = BigDecimal.ZERO;
        for (BigDecimal x : buckets) t = t.add(x != null ? x : BigDecimal.ZERO);
        return t;
    }
}
