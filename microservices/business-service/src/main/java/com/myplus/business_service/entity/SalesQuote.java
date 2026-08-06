package com.myplus.business_service.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import lombok.Data;

/**
 * B2B-P4b — a sales quote: a priced OFFER with a number, a shelf life and an approval trail.
 *
 * <h3>Not the same thing as a price calculation</h3>
 * {@code commerce-pricing} already answers "what does this basket cost right now" ({@code /price/calculate}).
 * That is arithmetic. This is a DOCUMENT: it is numbered, it is sent to a customer, it expires, it can require
 * internal approval, and it becomes evidence of what was promised. The programme plan's risk list calls for the
 * new one to be named {@code SalesQuote} from the start precisely so the two never get conflated.
 *
 * <h3>Prices are SNAPSHOTTED, not recomputed</h3>
 * The unit prices are resolved once (through the existing pricing path) and stored on the lines. A quote that
 * silently re-priced itself when a rule changed would not be a quote — the customer holds a piece of paper with
 * numbers on it, and those numbers must still be true when they accept.
 */
@Data
@Entity
@Table(name = "sales_quote", uniqueConstraints = {
        @UniqueConstraint(name = "uq_quote_org_seq", columnNames = {"organization_id", "quote_seq"}) })
public class SalesQuote implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Per-org running number. UNIQUE(organization_id, quote_seq) is what makes the MAX+1 allocation safe under
     * concurrency — the same guarantee invoice_seq has used since slice 22 and credit_note_seq since 3c.
     */
    @Column(name = "quote_seq")
    private Long quoteSeq;

    /** The document's own number, e.g. {@code QTE-000042}. */
    @Column(name = "quote_no", length = 32)
    private String quoteNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private QuoteStatus status = QuoteStatus.DRAFT;

    @Column(name = "customer_id")
    private Long customerId;

    /** Denormalised for the list screen — a quote must still read correctly if the customer is renamed later. */
    @Column(name = "customer_name")
    private String customerName;

    /**
     * The BUYER's own reference. Their accounts-payable clerk matches our invoice to their purchase order by
     * this, so it has to survive quote → sale → printed invoice; carrying it only on the quote would make it
     * useless to the person who asked for it.
     */
    @Column(name = "customer_po_number", length = 64)
    private String customerPoNumber;

    /** The offer's shelf life. EXPIRED is derived from this on read, never written by a job. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "sub_total", precision = 19, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "tax_total", precision = 19, scale = 2)
    private BigDecimal taxTotal;

    /** Whole-document trade discount. D-4: posts to a CONTRA-REVENUE account on conversion, not netted off sales. */
    @Column(name = "trade_discount", precision = 19, scale = 2)
    private BigDecimal tradeDiscount;

    @Column(name = "grand_total", precision = 19, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "notes", length = 500)
    private String notes;

    /** Who cleared the over-threshold discount, and when. Null when the quote never needed internal approval. */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** The invoice this quote became. Set once, at conversion — the QTE- → INV- trail. */
    @Column(name = "converted_invoice_no", length = 32)
    private String convertedInvoiceNo;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "dated", updatable = false)
    private LocalDateTime dated;

    @Column(name = "updated")
    private LocalDateTime updated;

    /**
     * Optimistic lock. Present from day one, not deferred: two staff converting the same quote at the same
     * moment would otherwise produce TWO invoices for one offer, which is a customer billed twice.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<SalesQuoteLine> lines = new ArrayList<>();

    /**
     * The status as the world should SEE it: an open quote past its validity reads EXPIRED even though no job
     * has touched the row. Derivation lives here so every reader — list, detail, convert guard — agrees.
     */
    // NOTE the getter NAME: Jackson serialises `getX()`, not `x()`. Called effectiveStatus() this computed
    // status never reached the client, so an expired quote would still have rendered as SENT in the UI while
    // the server refused every action on it — a confusing, silent mismatch. @Transient keeps JPA out of it.
    @Transient
    public QuoteStatus getEffectiveStatus() {
        if (status != null && status.isOpen() && validUntil != null && validUntil.isBefore(LocalDate.now()))
            return QuoteStatus.EXPIRED;
        return status;
    }
}
