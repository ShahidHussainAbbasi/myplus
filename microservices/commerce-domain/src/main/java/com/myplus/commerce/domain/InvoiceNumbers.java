package com.myplus.commerce.domain;

/**
 * Per-org invoice number formatting (slice 22 / slice 33 Phase 2). Pure, stateless: allocation of the
 * sequence (MAX+1 within the addSell transaction, guarded by the unique constraint) stays in the service
 * layer; only the display formatting lives here so every domain renders invoices identically.
 *
 * Format: {@code INV-000123} — fixed prefix + zero-padded {@value #WIDTH}-digit sequence.
 */
public final class InvoiceNumbers {

    public static final String PREFIX = "INV-";
    public static final int WIDTH = 6;

    private InvoiceNumbers() {}

    /** Format a per-org running sequence as a display invoice number, e.g. {@code 123 -> "INV-000123"}. */
    public static String format(long seq) {
        return PREFIX + String.format("%0" + WIDTH + "d", seq);
    }
}
