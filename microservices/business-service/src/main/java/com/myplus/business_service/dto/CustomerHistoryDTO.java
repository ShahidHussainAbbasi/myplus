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

    /**
     * RST-R2a — how the sale was served: {@code DINE_IN} / {@code TAKE_AWAY} / {@code DELIVERY}, or null.
     *
     * <p>A STRING on the wire rather than the enum, matching {@code customerType} beside it. The monolith
     * proxy re-serialises this payload, and a value it does not recognise must arrive as an unrecognised
     * string that {@code OrderType.byCode} resolves to null — not as a deserialisation failure that loses
     * the whole sale. Parsing happens once, server-side, where the refusal can be explained.
     *
     * <p>Null means "not recorded", which is what every sale in a shop without the capability sends.
     */
    private String orderType;

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

    /**
     * {@code pos.document.qtyDecimals} — does this tenant want two decimals on a QUANTITY?
     *
     * <p>A Boolean, not a boolean: null means the tenant never expressed a view, and the renderer treats
     * that as ON so a document nobody configured is the document they printed yesterday.
     */
    private Boolean qtyDecimals;

    /**
     * {@code pos.document.termsText} \u2014 the owner's terms block for the foot of the document.
     *
     * <p>Several lines, in whatever language the shop writes. Carried as one string with its newlines
     * intact; the renderer preserves them with {@code white-space:pre-line} rather than converting them to
     * markup, so nothing owner-authored is ever interpreted as HTML.
     */
    private String termsText;

    /**
     * {@code pos.document.fontFamily} \u2014 the tenant's chosen typeface for printed documents.
     *
     * <p>Blank means the built-in stack. Whatever is here is SANITISED in the renderer before it reaches a
     * CSS declaration: this is owner-supplied text on its way into a stylesheet, and a raw value could close
     * the declaration and open another.
     */
    private String fontFamily;

    /** {@code pos.document.numberSystem} — 'indian' or 'western'. Governs the figures AND the words. */
    private String numberSystem;

    /**
     * {@code pos.document.fiscalLine} — whatever a shop's tax authority requires on the face of an
     * invoice, in their own words.
     *
     * <p>Distinct from {@link #taxRegNo}, which is the sales-tax registration and already prints.
     */
    private String fiscalLine;

    // ── P1: how this tenant's documents reach paper. All absent => the browser dialog, i.e. today. ──
    /** {@code browser} | {@code escpos-raster} | {@code escpos-text}. */
    private String printMode;
    /** {@code agent} | {@code usb} | {@code serial}. Only consulted when printMode is a direct one. */
    private String printTransport;
    private String printAgentUrl;
    /** Dots across the roll: 576 for 80mm, 384 for 58mm. Both multiples of 8, as a raster row requires. */
    private Integer paperWidthDots;
    private Boolean cashDrawer;
    private Boolean autoCut;

    /**
     * The fiscal QR as a {@code data:image/png;base64,...} URI, or null when the tenant prints none.
     *
     * <p>Built SERVER-SIDE (DocumentQrService) so the HTML, the PDF and the thermal bitmap all carry the
     * same code. Three client-side generators would be three chances for the invoice a customer photographs
     * to disagree with the one that was filed.
     */
    private String qrDataUri;

    /**
     * The same code's PAYLOAD, unencoded.
     *
     * <p>ESC/POS text mode asks the printer to build the QR itself, which needs the text and not a picture
     * of it. Sent alongside the image rather than derived from it, because a data URI cannot be turned back
     * into its payload — and because both must come from one render of the template or the printed code
     * and the filed one could differ.
     */
    private String qrPayload;

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
     * Delivery charged to the customer on this invoice. Added AFTER tax, kept out of the goods subtotal and
     * the tax base, and credited to {@code 4300 Delivery Income}. Null on a counter sale.
     */
    private BigDecimal shippingFee;

    /**
     * The salesperson who booked the order, STAMPED on the invoice at write time rather than resolved from
     * {@code userId} at print time. Resolving it would put an auth-service round trip on the print path, and
     * would also print today's name for a person who has since been renamed — an issued document must not
     * change after the fact.
     */
    private String bookedByName;

    /**
     * B2B-P4b: the BUYER's own purchase-order reference, carried from the quote onto the invoice.
     *
     * <p>Their accounts-payable clerk matches our invoice to their PO by this number, so it has to survive
     * quote → sale → printed document. Capturing it only on the quote would make it useless to the one person
     * who asked for it.
     */
    private String customerPoNumber;

    /**
     * Things the cashier must be TOLD about a sale that still went through — currently the zero/negative
     * margin warning (#3). Server-populated on the way out; ignored on the way in.
     *
     * <p>Same pattern as {@code PrescriptionDTO.warnings}: the money has already changed hands by the time
     * these are raised, so anything the system could not enforce has to be said out loud rather than only
     * logged. Initialised so callers never null-check.
     */
    private java.util.List<String> warnings = new java.util.ArrayList<>();

    /**
     * SER-3 — the serials this sale claimed, gathered while the lines were built.
     *
     * <p><b>An out-parameter, not client input.</b> {@code buildLines} validates each line's serials against
     * the register while it already holds the ProductRef — asking catalog again per line would put a remote
     * call on the sale path — but it returns {@code List<SagaLine>} and has nowhere to hand them back. They
     * ride here to {@code addSell}, which marks them sold once the invoice exists.
     *
     * <p>Same shape and the same reason as {@link #warnings} directly above: something discovered during the
     * sale that the caller needs after it.
     */
    private java.util.List<String> serialsClaimed = new java.util.ArrayList<>();

    /**
     * INST-1 — present only when the cashier sold this item on terms; null on every ordinary sale.
     *
     * ⚠ This field exists in BOTH CustomerHistoryDTOs (monolith + business-service) and must stay that way.
     * The monolith binds this DTO and re-serialises it onward, so a block declared on one side only is
     * silently dropped in transit — the sale succeeds, the invoice is right, and the plan never exists.
     */
    private InstallmentPlanDTO installmentPlan;
}
