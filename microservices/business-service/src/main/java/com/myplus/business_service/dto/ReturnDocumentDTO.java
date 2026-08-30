package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Task #15 — a return, assembled into something a document can actually draw.
 *
 * <h3>Why this type exists</h3>
 * A {@code SaleReturn} / {@code PurchaseReturn} row cannot draw itself. It stores a {@code productId} but no
 * product name, a {@code venderId} but no vendor name, and on the sale side no customer at all — the customer
 * lives on the original {@code Sell} → {@code CustomerHistory}. So printing a return means resolving several
 * things the row only points at, and this is the resolved result.
 *
 * <h3>One note is one line — today</h3>
 * {@code saleReturn} takes a single {@code sellId} and {@code purchaseReturn} a single {@code purchaseId},
 * each allocating a fresh note number, so a note currently maps to exactly one row and {@link #lines} always
 * has one element.
 *
 * <p>It is a LIST anyway, deliberately. Because a note is allocated per line, a customer returning three items
 * receives three credit notes; if that is ever changed to one note per return, a single-line model would have
 * to be thrown away and would take the preset, the PDF layout and the designer bindings with it. A list of one
 * costs nothing now and makes that a data change rather than a rewrite.
 *
 * <h3>Naming</h3>
 * These are a CREDIT NOTE (sale return) and a DEBIT NOTE (purchase return), which is both the correct trade
 * term and what the schema already calls them ({@code credit_note_no} / {@code debit_note_no}). Not "return
 * invoices" — a credit note is not an invoice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnDocumentDTO {

    /** {@code CREDIT_NOTE} or {@code DEBIT_NOTE} — selects the preset the renderer draws with. */
    private String documentType;

    /** This document's OWN number, e.g. {@code CRN-000007} / {@code DBN-000012}. */
    private String documentNo;

    /**
     * The document being reversed — the sale invoice or the purchase bill.
     *
     * <p>A reference, not this document's identity. Referencing the reversed document is the accounting rule,
     * and before the note numbers existed this was the ONLY number a return had, which made a credit note
     * indistinguishable from the invoice it cancelled.
     */
    private String referenceNo;

    /** ISO date of the return itself, not of the invoice it reverses. */
    private String dated;

    /** Customer (credit note) or vendor (debit note), resolved for display. */
    private String partyName;

    /** Why the goods came back. Free text, shown on the document because a supplier will ask. */
    private String reason;

    private List<Line> lines;

    /**
     * The document's FACE VALUE — returned goods plus their tax.
     *
     * <p>On the sale side this is {@code SaleReturn.creditAmount}, and specifically NOT {@code refundAmount}:
     * refundAmount is only the cash handed back and is zero on a credit sale, so it could never serve as the
     * document's value. A null creditAmount means the return predates V34 and its value is unrecoverable — the
     * endpoint REFUSES to build a document in that case rather than printing a zero onto a customer-facing
     * note, matching what the statement already does (it omits the line rather than inventing a number).
     */
    private BigDecimal totalAmount;

    /**
     * Cash actually handed back, sale side only. Distinct from {@link #totalAmount} and worth showing: a
     * customer needs to see that a credit sale's return moved their balance rather than their pocket.
     */
    private BigDecimal refundedCash;

    /** Which store took the return — null on single-store / legacy tenants. */
    private Long storeId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {

        private Long productId;

        /** Resolved from the catalog {@code ProductRef}; the row itself only holds the id. */
        private String productName;

        private String sku;

        /**
         * Returned quantity. A distributor returns by weight as well as by count, so this must be able to
         * express 1.5 kg — the same reason {@code Sell.quantity} is a float rather than an integer.
         */
        private BigDecimal quantity;

        /** Unit rate from the original sold/purchased line, where it can be resolved. */
        private BigDecimal rate;

        /** This line's share of the note's value. With one line per note it equals the total. */
        private BigDecimal amount;
    }
}
