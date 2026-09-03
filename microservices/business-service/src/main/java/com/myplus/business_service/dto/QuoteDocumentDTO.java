package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A sales quote, resolved for PRINTING — task #28.
 *
 * <h3>Why this exists when {@code /getQuote} already returns the whole quote</h3>
 * A quote row knows its customer's id and the name it was raised under. A printed quote also needs the
 * customer's address and phone, because it is handed to a person who has to recognise it as theirs. Rather
 * than make the client fetch a quote and then a customer and hope the two agree, the document is assembled
 * once, server-side, from the same read.
 *
 * <h3>⚠ {@link #effectiveStatus}, never the raw status</h3>
 * {@code EXPIRED} is DERIVED, not stored — a quote past its {@code validUntil} still holds
 * {@code status = SENT} in its row (see {@code SalesQuote.getEffectiveStatus}). A document built from the
 * stored field would print SENT onto a sheet the server refuses every action on: a priced offer a customer
 * would reasonably expect to be honoured. This field carries the same answer the convert guard uses, so the
 * paper and the system cannot disagree.
 *
 * <h3>Printed at EVERY stage</h3>
 * There is deliberately no status filter on the endpoint that builds this. A rep prints a DRAFT to check it,
 * sends the SENT one, files the ACCEPTED one against the customer's PO, and reprints the CONVERTED one when
 * the invoice is queried months later. Restricting print to one stage would mean the document exists only at
 * the moment nobody needs it. What the stage changes is how the sheet is MARKED, not whether it prints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteDocumentDTO {

    /** This quote's own number, e.g. {@code QTE-000021}. Allocated at creation, so a DRAFT already has one. */
    private String quoteNo;

    /** ISO date the quote was raised. */
    private String dated;

    /**
     * The last day this offer stands.
     *
     * <p>Always printed. A quote without an expiry reads as an open-ended promise, and the whole reason the
     * EXPIRED state exists is that prices move.
     */
    private String validUntil;

    /**
     * DRAFT / PENDING_APPROVAL / SENT / ACCEPTED / REJECTED / EXPIRED / CONVERTED.
     *
     * <p>The renderer prints this as a status band, and watermarks the page for any value that is not a live
     * offer. A printed DRAFT that does not say DRAFT is a firm offer — that is the safety property of the
     * whole slice, and this field is what carries it.
     */
    private String effectiveStatus;

    private String customerName;

    /** Resolved from the customer record, not stored on the quote — a document is handed to a person. */
    private String customerAddress;

    private String customerMobile;

    /** The customer's own reference, printed so their purchasing team can match it to their paperwork. */
    private String customerPoNumber;

    /**
     * Set only once the quote became a sale. Printed so a converted sheet explains itself: this was quoted,
     * and it became that. Without it a converted quote and a live offer look identical on paper, which is how
     * a customer ends up billed twice for one agreement.
     */
    private String convertedInvoiceNo;

    /** Free text from the quote — terms, delivery, anything the rep wrote for the customer to read. */
    private String notes;

    private List<Line> lines;

    private BigDecimal subTotal;

    /** Contra-revenue trade discount (D-4), shown as its own line so the customer sees what was given. */
    private BigDecimal tradeDiscount;

    private BigDecimal taxTotal;

    private BigDecimal grandTotal;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {

        private Long productId;

        /** Stamped on the quote line at creation, so the document still reads correctly after a rename. */
        private String productName;

        /**
         * Quoted quantity. A distributor quotes by weight as well as by count, so this must express 1.5 kg —
         * the same reason {@code SalesQuoteLine.quantity} is a float.
         */
        private BigDecimal quantity;

        private BigDecimal unitPrice;

        private BigDecimal discount;

        private BigDecimal lineTotal;
    }
}
