package com.myplus.common.subledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Slice 0.2a — the extracted aging + statement engines, now shared by POS (AR/AP) and education (fees).
 *
 * These moved out of business-service, so this test lives with them: it proves the extraction preserved
 * behaviour, and it guards the contract every future vertical will depend on. Pure — no Spring, no DB.
 */
class AgingAndStatementTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    private static AgingCalculator.AgingRow row(String amt, LocalDate d) {
        return new AgingCalculator.AgingRow(new BigDecimal(amt), d);
    }

    @Test @DisplayName("Each amount lands in the bucket matching its age")
    void bucketsByAge() {
        BigDecimal[] b = AgingCalculator.bucketize(List.of(
                row("100", AS_OF.minusDays(10)),    // 0–30
                row("200", AS_OF.minusDays(45)),    // 31–60
                row("300", AS_OF.minusDays(75)),    // 61–90
                row("400", AS_OF.minusDays(200))    // 90+
        ), AS_OF);
        assertEquals(new BigDecimal("100"), b[0]);
        assertEquals(new BigDecimal("200"), b[1]);
        assertEquals(new BigDecimal("300"), b[2]);
        assertEquals(new BigDecimal("400"), b[3]);
    }

    @Test @DisplayName("A future-dated document counts as current, not as overdue")
    void futureDatedIsCurrent() {
        // A fee charged for next month must not appear as arrears — it would overstate what a parent owes now.
        BigDecimal[] b = AgingCalculator.bucketize(List.of(row("500", AS_OF.plusDays(15))), AS_OF);
        assertEquals(new BigDecimal("500"), b[0]);
        assertEquals(BigDecimal.ZERO, b[3]);
    }

    @Test @DisplayName("Settled documents are skipped")
    void nonPositiveSkipped() {
        BigDecimal[] b = AgingCalculator.bucketize(List.of(
                row("0", AS_OF.minusDays(200)), row("-50", AS_OF.minusDays(200))), AS_OF);
        for (BigDecimal x : b) assertEquals(BigDecimal.ZERO, x);
    }

    @Test @DisplayName("A statement's running balance rises on a charge and falls on a payment")
    void statementRunningBalance() {
        List<StatementLine> lines = new ArrayList<>(List.of(
                new StatementLine(AS_OF.minusDays(60), "1", "FEE_CHARGE", new BigDecimal("3000"), BigDecimal.ZERO, null),
                new StatementLine(AS_OF.minusDays(30), "2", "FEE_CHARGE", new BigDecimal("3000"), BigDecimal.ZERO, null),
                new StatementLine(AS_OF.minusDays(20), "2", "PAYMENT", BigDecimal.ZERO, new BigDecimal("4000"), null)));

        List<StatementLine> out = StatementBuilder.build(lines, BigDecimal.ZERO);

        assertEquals(new BigDecimal("3000"), out.get(0).getBalance());
        assertEquals(new BigDecimal("6000"), out.get(1).getBalance());
        assertEquals(new BigDecimal("2000"), out.get(2).getBalance());   // closing balance still owed
    }

    @Test @DisplayName("Lines are ordered by date regardless of input order")
    void statementSortsByDate() {
        List<StatementLine> lines = new ArrayList<>(List.of(
                new StatementLine(AS_OF, "2", "PAYMENT", BigDecimal.ZERO, new BigDecimal("100"), null),
                new StatementLine(AS_OF.minusDays(10), "1", "FEE_CHARGE", new BigDecimal("100"), BigDecimal.ZERO, null)));
        List<StatementLine> out = StatementBuilder.build(lines, BigDecimal.ZERO);
        assertEquals("FEE_CHARGE", out.get(0).getType());
        assertEquals(BigDecimal.ZERO, out.get(1).getBalance());
    }
}
