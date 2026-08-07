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

    /**
     * B2B-P4b: prefix for a SALES QUOTE — an offer, not a sale.
     *
     * <p>Its own series for the same reason credit notes got one: a quote is a distinct document with a
     * different meaning and lifecycle, and sharing the invoice series would make an offer indistinguishable
     * from money owed. The quote number stays with the invoice it converts into, so the trail runs
     * {@code QTE-000042 → INV-000123}.
     */
    public static final String QUOTE_PREFIX = "QTE-";

    /**
     * OMS O2: prefix for a SALES ORDER — the merchant-facing reference for a storefront order.
     *
     * <p>Public tracking used the raw auto-increment id, which is guessable (so the id space could be probed
     * across tenants) and useless to quote on the phone. Its own per-org series fixes both.
     */
    public static final String ORDER_PREFIX = "SO-";

    /**
     * OMS O5b: prefix for a SHIPMENT — one parcel that physically left, not the order it belongs to.
     *
     * <p>Its own series because an order can ship in parts: {@code SO-000123} may produce {@code SHP-000045} and
     * {@code SHP-000046}, and the customer needs to name the parcel they are asking about. Reusing the order
     * number would make two dispatches indistinguishable — the same reason credit notes stopped reusing the
     * invoice series.
     */
    public static final String SHIPMENT_PREFIX = "SHP-";

    /** Format a per-org shipment sequence, e.g. {@code 45 -> "SHP-000045"} (OMS O5b). */
    public static String shipment(long seq) {
        return pad(SHIPMENT_PREFIX, seq);
    }

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

    /** Format a per-org quote sequence, e.g. {@code 42 -> "QTE-000042"} (B2B-P4b). */
    public static String quote(long seq) {
        return pad(QUOTE_PREFIX, seq);
    }

    /** Format a per-org order sequence, e.g. {@code 123 -> "SO-000123"} (OMS O2). */
    public static String order(long seq) {
        return pad(ORDER_PREFIX, seq);
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
