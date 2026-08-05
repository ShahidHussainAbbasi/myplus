package com.myplus.business_service.dto;
import java.math.BigDecimal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * 
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerHistoryDTO {

    private Long customer_history_id;

	private LocalDateTime dated;

	private LocalDateTime updated;

	private Long userId;

	private String userType;

	private CustomerDTO customer;

	private Float receivedAmount;

	private List<SellDTO> sales = new ArrayList<>();

	private BigDecimal paidAmount;

    private BigDecimal dueAmount;

    private LocalDate dueDate;

    private Long invoiceSeq;     // per-org running number (slice 22)

    /**
     * Pharmacy (review B1): the prescription this sale dispenses, when the cashier started from Dispense. Its
     * PRESENCE is what lets a prescription-only line through the sell guard; whether the script actually covers
     * the basket is reconciled straight after by pharma-service's dispense call (which warns on off-script and
     * capped quantities) — verifying it here would put pharma-service on the checkout path.
     */
    private Long prescriptionId;

    private String invoiceNo;    // display invoice number, e.g. INV-000123

    // G3 (slice 35): invoice tax summary for the receipt + tax report.
    private BigDecimal subTotal;

    private BigDecimal taxTotal;

    private BigDecimal grandTotal;

    // G5 (slice 37): tenders entered at checkout (in) + the settled payment summary (out).
    private List<TenderDTO> tenders = new ArrayList<>();

    private String paymentMode;

    private BigDecimal tenderedAmount;

    private BigDecimal changeAmount;

    private BigDecimal storeCreditApplied;   // SF-5 Model B: store credit redeemed on this sale (for the receipt)

    // SF-3: client-supplied idempotency key (one per checkout attempt). addSell dedups on (org, key) so a
    // double-click / network retry records ONE invoice instead of two. Null for legacy callers.
    private String idempotencyKey;

    // G6 (slice 38): receipt header bits from the org tax policy (not persisted on the invoice).
    private String taxLabel;

    private String taxRegNo;

    // common-settings (pos.receipt.showTaxBreakdown): whether the receipt should list tax per rate. The owner
    // toggles this on the Configuration screen; the client honours it (default true when absent = back-compat).
    private Boolean showTaxBreakdown;

    // common-settings (pos.receipt.showPromo): whether to print the "Powered by MaxTheService" footer.
    // OFF unless the org opted in — this appears on a document our customer hands to THEIR customer, so
    // absent/null means off (unlike a safety flag, where absent means on).
    private Boolean showPromo;

    /**
     * B2B-P3b-2 (#4): what the customer owed in total right after this invoice. A SNAPSHOT taken at sale
     * time — the current balance would put today's figure on a reprint of an old invoice. The receipt derives
     * "previous balance" from it, so the two can never disagree. Null on pre-existing invoices.
     */
    private BigDecimal balanceAfter;

    /**
     * B2B P1 (#9): the operator has SEEN the credit-limit warning and chosen to continue.
     *
     * <p>Inbound only. Under {@code warn} the first submit of an over-limit sale is answered {@code CONFIRM}
     * with nothing written; the client asks, and re-submits with this set. Under {@code block} it is ignored
     * entirely — that is the whole difference between the two policies: nobody on the till can consent past
     * {@code block}.
     */
    private Boolean creditAcknowledged;

    // ---------------------------------------------------------------- B2B Phase 3g: document rendering
    //
    // Everything below is OUTBOUND decoration for the printable document. It is deliberately part of the
    // SAME payload rather than a second endpoint: a document is one thing, and fetching its layout
    // separately from its contents invites the two to disagree about which invoice is being printed.
    // Every field is null-safe — absent means the renderer falls back to today's behaviour.

    /** Who ISSUED this document (settings → Store). Replaces printing our own brand on a tenant's invoice. */
    private LetterheadDTO letterhead;

    /**
     * {@code pos.document.layoutMode} — {@code auto} (the buyer's channel decides), {@code thermal} or
     * {@code a4}. The per-org override for a shop that wants one format for everything.
     */
    private String layoutMode;

    /** The org's stored Document Profile for this channel (3g-3). Null ⇒ the renderer uses a built-in preset. */
    private Object documentProfile;

    private String currencySymbol;

    private String currencyWord;

    private String currencyFraction;

    private String footerText;

    private Boolean showAmountInWords;

    /**
     * B2B-P3g: an invoice-level trade discount, as distinct from the per-line discounts already carried on
     * {@code Sell.discount}. A distribution invoice settles a whole-order concession here, and before 3g
     * there was no column for it anywhere in the schema.
     */
    private BigDecimal tradeDiscount;

    /**
     * The salesperson who booked the order, STAMPED on the invoice at write time rather than resolved from
     * {@code userId} at print time. Resolving it would put an auth-service round trip on the print path, and
     * would also print today's name for a person who has since been renamed — an issued document must not
     * change after the fact.
     */
    private String bookedByName;

    /**
     * Things the cashier must be TOLD about a sale that still went through — currently the zero/negative
     * margin warning (#3). Server-populated on the way out; ignored on the way in.
     *
     * <p>Same pattern as {@code PrescriptionDTO.warnings}: the money has already changed hands by the time
     * these are raised, so anything the system could not enforce has to be said out loud rather than only
     * logged. Initialised so callers never null-check.
     */
    private java.util.List<String> warnings = new java.util.ArrayList<>();
}
