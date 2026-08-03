package com.myplus.commerce.domain;

/**
 * Per-org invoice number formatting (slice 22 / slice 33 Phase 2). Pure, stateless: allocation of the
 * sequence (MAX+1 within the addSell transaction, guarded by the unique constraint) stays in the service
 * layer; only the display formatting lives here so every domain renders invoices identically.
 *
 * Format: {@code INV-000123} — fixed prefix + zero-padded {@value #WIDTH}-digit sequence.
 *
 * <p>B2B-P3c (#1): returns are NOT invoices. A customer return is a <b>credit note</b> ({@code CRN-}) and a
 * supplier return a <b>debit note</b> ({@code DBN-}) — distinct documents in their own series that
 * <i>reference</i> the document they reverse. Reusing the invoice number, as the code did before, makes a
 * credit note indistinguishable from the invoice it cancels and reconciliation impossible.
 */
public final class InvoiceNumbers {

    public static final String PREFIX = "INV-";
    public static final int WIDTH = 6;

    private InvoiceNumbers() {}

    /** Prefix for a CUSTOMER return document — a credit note. */
    public static final String CREDIT_NOTE_PREFIX = "CRN-";

    /** Prefix for a SUPPLIER return document — a debit note. */
    public static final String DEBIT_NOTE_PREFIX = "DBN-";

    /** Format a per-org running sequence as a display invoice number, e.g. {@code 123 -> "INV-000123"}. */
    public static String format(long seq) {
        return pad(PREFIX, seq);
    }

    /** Format a per-org credit-note sequence, e.g. {@code 7 -> "CRN-000007"} (customer return). */
    public static String creditNote(long seq) {
        return pad(CREDIT_NOTE_PREFIX, seq);
    }

    /** Format a per-org debit-note sequence, e.g. {@code 7 -> "DBN-000007"} (supplier return). */
    public static String debitNote(long seq) {
        return pad(DEBIT_NOTE_PREFIX, seq);
    }

    /**
     * True if the number denotes a RETURN document rather than a sale. Lets a report or ledger line tell the
     * two apart without knowing which series it came from.
     */
    public static boolean isReturnDocument(String number) {
        return number != null
                && (number.startsWith(CREDIT_NOTE_PREFIX) || number.startsWith(DEBIT_NOTE_PREFIX));
    }

    private static String pad(String prefix, long seq) {
        return prefix + String.format("%0" + WIDTH + "d", seq);
    }
}
