package com.myplus.commerce.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2B-P3c (#1): a return is a DIFFERENT DOCUMENT from the sale it reverses — a credit note (customer) or a
 * debit note (supplier). Pure formatting, so this runs on every {@code mvn test} without Spring.
 */
class InvoiceNumbersTest {

    @Test
    @DisplayName("an invoice number is unchanged — this must not drift")
    void invoiceFormatIsStable() {
        assertEquals("INV-000123", InvoiceNumbers.format(123));
        assertEquals("INV-000001", InvoiceNumbers.format(1));
    }

    @Test
    @DisplayName("a customer return is a CREDIT note, in its own series")
    void creditNote() {
        assertEquals("CRN-000007", InvoiceNumbers.creditNote(7));
        assertEquals("CRN-000123", InvoiceNumbers.creditNote(123));
    }

    @Test
    @DisplayName("a supplier return is a DEBIT note, in its own series")
    void debitNote() {
        assertEquals("DBN-000007", InvoiceNumbers.debitNote(7));
    }

    @Test
    @DisplayName("THE point of the slice: a credit note can never be mistaken for the invoice it reverses")
    void aCreditNoteIsNotAnInvoice() {
        // Same sequence number, three different documents. Before this, a return reused the sale's number.
        assertNotEquals(InvoiceNumbers.format(42), InvoiceNumbers.creditNote(42));
        assertNotEquals(InvoiceNumbers.format(42), InvoiceNumbers.debitNote(42));
        assertNotEquals(InvoiceNumbers.creditNote(42), InvoiceNumbers.debitNote(42));
    }

    @Test
    @DisplayName("a ledger line can tell a return document from a sale without knowing the series")
    void returnDocumentsAreIdentifiable() {
        assertTrue(InvoiceNumbers.isReturnDocument("CRN-000001"));
        assertTrue(InvoiceNumbers.isReturnDocument("DBN-000001"));
        assertFalse(InvoiceNumbers.isReturnDocument("INV-000001"));
        assertFalse(InvoiceNumbers.isReturnDocument(null), "a missing number is not a return");
    }

    @Test
    @DisplayName("the sequence widens past six digits rather than truncating")
    void largeSequencesAreNotTruncated() {
        // A busy tenant WILL pass 999,999. Losing digits would collide two real documents.
        assertEquals("CRN-1234567", InvoiceNumbers.creditNote(1234567));
    }
}
