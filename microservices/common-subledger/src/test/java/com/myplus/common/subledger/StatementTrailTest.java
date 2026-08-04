package com.myplus.common.subledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2B-P3f — the statement document trail: an issued bill, the credit/debit notes that reduce it, and a void.
 *
 * <p>The defect this guards: a return used to rewrite the invoice header, so an invoice issued at 500 read as
 * a single BILL of 300 and the credit note appeared nowhere. The bill is now the issued value and the note is
 * its own line — so the arithmetic below must land on the SAME closing balance as the old netted line did.
 * If a balance in this file moves, the slice has broken something it promised not to touch.
 *
 * <p>Pure — no Spring, no DB. Runs on {@code mvn test}, which Cypress cannot substitute for.
 */
class StatementTrailTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 1);

    private static StatementLine bill(LocalDate d, String no, String amt) {
        return new StatementLine(d, no, "BILL", new BigDecimal(amt), null, null);
    }

    private static StatementLine credit(LocalDate d, String no, String type, String amt) {
        return new StatementLine(d, no, type, null, amt == null ? null : new BigDecimal(amt), null);
    }

    @Test @DisplayName("Issued bill + credit note + payment closes at zero, not at the netted bill")
    void creditNoteReducesTheIssuedBill() {
        // The whole point of 3f: 500 is what was ISSUED (and what the customer's copy says), CRN-7 explains
        // the 200, and the closing balance is identical to the 300-bill statement this replaces.
        List<StatementLine> out = StatementBuilder.build(new ArrayList<>(List.of(
                bill(D1, "INV-100", "500.00"),
                credit(D1.plusDays(2), "CRN-7", "CREDIT_NOTE", "200.00"),
                credit(D1.plusDays(4), "RCT-22", "PAYMENT", "300.00"))), BigDecimal.ZERO);

        assertEquals(new BigDecimal("500.00"), out.get(0).getBalance());
        assertEquals(new BigDecimal("300.00"), out.get(1).getBalance());   // == the old netted BILL line
        assertEquals(new BigDecimal("0.00"), out.get(2).getBalance());
    }

    @Test @DisplayName("A void nets its own bill to zero")
    void voidNetsItsBill() {
        // voidSell zeroes the header, so without the VOID line the issued bill would stand alone and overstate
        // the invoice by its full value — a bug 3f would have INTRODUCED rather than found.
        List<StatementLine> out = StatementBuilder.build(new ArrayList<>(List.of(
                bill(D1, "INV-101", "500.00"),
                credit(D1.plusDays(1), "INV-101", "VOID", "500.00"))), BigDecimal.ZERO);

        assertEquals(new BigDecimal("0.00"), out.get(1).getBalance());
    }

    @Test @DisplayName("A note with no stored value contributes nothing and does not blow up")
    void valuelessNoteIsInert() {
        // Pre-V34 credit notes have no value (unrecoverable — a full return deleted the sell row). The repository
        // filters them out; this pins that one arriving anyway leaves the balance alone instead of NPEing.
        List<StatementLine> out = StatementBuilder.build(new ArrayList<>(List.of(
                bill(D1, "INV-102", "400.00"),
                credit(D1.plusDays(1), "CRN-1", "CREDIT_NOTE", null))), BigDecimal.ZERO);

        assertEquals(new BigDecimal("400.00"), out.get(1).getBalance());
    }

    @Test @DisplayName("A debit note reduces a vendor bill the same way — one engine, both parties")
    void debitNoteReducesAVendorBill() {
        List<StatementLine> out = StatementBuilder.build(new ArrayList<>(List.of(
                bill(D1, "PINV-9", "1000.00"),
                credit(D1.plusDays(3), "DBN-2", "DEBIT_NOTE", "250.00"))), BigDecimal.ZERO);

        assertEquals(new BigDecimal("750.00"), out.get(1).getBalance());
    }

    @Test @DisplayName("A note dated the same day as its bill still sorts after it")
    void sameDayNoteFollowsItsBill() {
        // Same-day return is the common case at a till. The builder sorts by date only, so this relies on the
        // sort being STABLE and on the service adding bills before notes — pinned here because a statement that
        // opens with a credit would show a negative balance the customer never had.
        List<StatementLine> out = StatementBuilder.build(new ArrayList<>(List.of(
                bill(D1, "INV-103", "300.00"),
                credit(D1, "CRN-9", "CREDIT_NOTE", "100.00"))), BigDecimal.ZERO);

        assertEquals("BILL", out.get(0).getType());
        assertEquals(new BigDecimal("300.00"), out.get(0).getBalance());
        assertEquals(new BigDecimal("200.00"), out.get(1).getBalance());
    }
}
