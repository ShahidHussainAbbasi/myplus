package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.CustomerHistory;

/**
 * L18 — reversing an opening balance KEEPS the document, zeroed and stamped VOID, and returns the amount the
 * reversing journal must take back.
 *
 * <p>Before L18 the row was deleted and no journal posted: the ledger kept 675,000.00 of receivables across 15
 * reversals on the test tenant. Each case asserts something that broke, or would break, that money.
 */
class OpeningBalanceReversalTest {

    private static CustomerHistory opening(String amount) {
        CustomerHistory ch = new CustomerHistory();
        ch.setInvoiceNo("OB-000042");
        ch.setDocType(OpeningBalanceService.DOC_OPENING);
        ch.setGrandTotal(new BigDecimal(amount));
        ch.setPaidAmount(BigDecimal.ZERO);
        ch.setDueAmount(new BigDecimal(amount).negate());
        return ch;
    }

    @Test
    void theReversalReturnsThePostedAmount_soTheJournalTakesBackExactlyIt() {
        CustomerHistory ch = opening("45000.00");
        assertEquals(new BigDecimal("45000.00"),
                OpeningBalanceService.applyReversal(ch, 7L, " typo ", LocalDateTime.of(2026, 10, 1, 9, 0)));
    }

    @Test
    void theDocumentIsKept_zeroed_andStillSaysWhatWasOwed() {
        CustomerHistory ch = opening("45000.00");
        OpeningBalanceService.applyReversal(ch, 7L, " typo ", LocalDateTime.of(2026, 10, 1, 9, 0));
        // Zeroed: the customer balance (sumDueByCustomer) and the opening total (sumOpeningForOrg) drop it.
        assertEquals(0, ch.getGrandTotal().signum());
        assertEquals(0, ch.getDueAmount().signum());
        // Kept: the statement shows the balance AND its reversal instead of going blank.
        assertEquals(new BigDecimal("45000.00"), ch.getIssuedTotal());
        assertEquals("VOID", ch.getStatus());
        assertEquals(Long.valueOf(7L), ch.getVoidedBy());
        assertEquals("typo", ch.getVoidReason());
        assertNotNull(ch.getVoidedAt());
    }

    @Test
    void aSecondReversalIsRefused_itWouldPostASecondCreditToReceivables() {
        CustomerHistory ch = opening("45000.00");
        assertNull(OpeningBalanceService.refuseReversal(ch));
        OpeningBalanceService.applyReversal(ch, 7L, "typo", LocalDateTime.now());
        String why = OpeningBalanceService.refuseReversal(ch);
        assertNotNull(why);
        assertTrue(why.contains("already been reversed"), why);
    }

    @Test
    void aPartPaidOpeningBalanceIsStillRefused() {
        CustomerHistory ch = opening("45000.00");
        ch.setPaidAmount(new BigDecimal("5000.00"));
        assertTrue(OpeningBalanceService.refuseReversal(ch).contains("PAID in part"));
    }

    @Test
    void aSaleIsNeverReversedHere() {
        CustomerHistory ch = opening("45000.00");
        ch.setDocType(OpeningBalanceService.DOC_SALE);
        assertTrue(OpeningBalanceService.refuseReversal(ch).contains("is a sale"));
    }

    @Test
    void theStatementNamesAnOpeningBalance_andStillCallsASaleABill() {
        assertEquals("OPENING", FinanceReportService.debitLineType(opening("1.00")));
        CustomerHistory sale = opening("1.00");
        sale.setDocType(OpeningBalanceService.DOC_SALE);
        assertEquals("BILL", FinanceReportService.debitLineType(sale));
    }
}
