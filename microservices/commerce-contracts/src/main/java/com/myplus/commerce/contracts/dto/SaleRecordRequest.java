package com.myplus.commerce.contracts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OMS O1 — "record a sale for a completed order", the request half of the missing seam between a channel and
 * the books.
 *
 * <p><b>Why this exists.</b> Every channel that takes money must produce exactly ONE invoice through exactly
 * ONE revenue path. Before O1 the storefront ran its own reserve → charge → confirm saga and wrote an
 * {@code Order}, but never created a trade sale — so an online sale decremented stock and charged a card while
 * producing no invoice, no revenue journal, no tax-register line, no AR and no payment row. P&amp;L, trial
 * balance, tax register, period close and day close were all silently wrong for online sales.
 *
 * <p><b>What it deliberately does NOT carry: totals.</b> There is no {@code total}, {@code subTotal} or
 * {@code taxTotal} field. business-service recomputes every figure from the lines, exactly as it does for a POS
 * sale — the caller states what was BOUGHT, never what it came to. A client-supplied total is the OMS-5 defect,
 * and the way to make it unrepresentable is to leave it out of the contract.
 *
 * <p>{@code idempotencyKey} is the contract's safety property: the same key always yields the same invoice, so a
 * retry, a double-submit or a relay re-drive can never mint a second one.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SaleRecordRequest {

    /** Same key → same invoice, always. Required; the sale path is idempotent on it. */
    private String idempotencyKey;

    /** Tenant the sale belongs to. The internal endpoint runs as this org, never as the caller's own. */
    private Long organizationId;

    /** Where the sale came from: STOREFRONT | POS | … — recorded for reporting, not for pricing. */
    private String channel;

    /** Optional store/location the sale is attributed to (multi-location). Null = single-store. */
    private Long storeId;

    private Customer customer;

    @Builder.Default
    private List<Line> lines = new ArrayList<>();

    /** What was actually paid, and how. Empty = nothing paid yet (COD), which becomes a receivable. */
    @Builder.Default
    private List<Tender> tenders = new ArrayList<>();

    /**
     * A whole-document concession the channel granted — a coupon, a promo, a negotiated allowance.
     *
     * <h3>Why this is not "a total" and does not violate the rule above</h3>
     * The paragraph above forbids the caller stating what the sale CAME TO, because the server must price the
     * goods. This is not that. A coupon is a FACT ABOUT THE TRANSACTION that only the channel knows — the
     * server cannot recompute it from the lines because the promotion lives in the channel's own coupon rules.
     * business-service still prices every line itself and still derives every total; it simply subtracts a
     * concession it has been told about instead of pretending it did not happen.
     *
     * <h3>What it does to the books</h3>
     * It is CONTRA-REVENUE, not a price cut — the same treatment B2B D-4 settled for the trade discount. Sales
     * is credited at the goods' list value and the concession is debited to {@code 4200 Sales Discount}, so
     * gross revenue still reads at face value and the cost of promotions is visible as its own line rather
     * than quietly eroding Sales.
     *
     * <p>Null or zero = no concession, which is every till sale, and leaves the journal byte-for-byte
     * unchanged.
     */
    private BigDecimal discountTotal;

    /**
     * Delivery charged to the customer, in the sale's currency. Added to the invoice AFTER tax and credited to
     * {@code 4300 Delivery Income}, so it never enters the goods subtotal or the tax base.
     *
     * <h3>Why it is not taxed</h3>
     * The storefront quote adds this fee after tax and does not tax it. Taxing it here would put the quote and
     * the invoice back into disagreement — which is the exact defect this contract exists to prevent. Making
     * delivery taxable is a legitimate policy question, but it has to change the quote and the invoice
     * together, not one of them.
     *
     * <p>Without this field the fee the shopper paid simply never reached the books: the order row carried it,
     * the invoice did not, and delivery income stayed off the P&amp;L entirely.
     */
    private BigDecimal shippingFee;

    /** Free text carried onto the sale (e.g. the storefront order number). */
    private String notes;

    /**
     * The buyer. {@code partyId} links to the shared contact master when the channel already knows it;
     * otherwise business-service resolves or creates the customer from name + contact as it does at the till.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Customer {
        private Long customerId;
        private String name;
        private String contact;
        private Long partyId;
        private String address;
    }

    /**
     * One sale line. {@code unitPrice} is what the channel quoted the shopper; business-service still applies
     * its own tax and cost logic on top, and reserves stock FEFO by {@code productId}.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Line {
        private Long productId;
        private Float quantity;
        private BigDecimal unitPrice;
        private Long taxCodeId;
        private String description;
        /**
         * A concession on THIS line, as an amount off (qty × unitPrice).
         *
         * <h3>Why the line carries it rather than the price being lowered</h3>
         * A rep negotiates per product. Expressing that by sending a lower {@code unitPrice} arrives at the
         * same money and destroys the information: the invoice then shows a cheaper trade price instead of
         * "list, less discount", the shopkeeper cannot see what they were given, and the distributor cannot
         * total what they gave away. {@code discountTotal} above is a different thing — one concession at the
         * foot of the document, not one per product.
         *
         * <h3>What the receiver does with it</h3>
         * Nothing new. business-service's sale path already resolves a per-line discount and — importantly —
         * taxes the DISCOUNTED base, so this field only had to reach it. An amount, not a percentage, because
         * that is what the invoice line stores; a percentage on the wire would leave two services deciding
         * what it is a percentage of.
         *
         * <p>Null or zero on an undiscounted line, which is every POS and storefront line today.
         */
        private BigDecimal discount;

        /**
         * The serial / IMEI of the unit(s) this line is selling.
         *
         * <h3>Its absence made serial-tracked goods unsellable through this API</h3>
         * business-service refuses a line for a {@code requiresSerial} product that names no serial — correctly,
         * and identically for a till sale and an order dispatch. But this wire carried no way to name one, so
         * every storefront order and every OMS dispatch of a handset was refused with "scan or enter the one
         * being sold" and no field in which to answer. Proven against a running stack: passing a serial in the
         * body changed nothing, because {@code toCustomerHistory} had nothing to copy.
         *
         * <p>One field for the whole line, newline- or comma-separated, matching {@code SellDTO.serials} and
         * split by the same {@code SerialUnitService.split}. A list would have been the obvious shape and the
         * wrong one — the POS side is a single string for transport reasons that have not gone away, and two
         * shapes for one concept is how a field arrives populated and is read as empty.
         *
         * <p>Null on every line of every ordinary sale, which is most of them.
         */
        private String serials;
    }

    /** One payment against the sale. {@code reference} carries the PSP charge id for a CARD tender. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tender {
        private String method;       // CASH | CARD | COD | CREDIT | WALLET | BANK_TRANSFER
        private BigDecimal amount;
        private String reference;
    }
}
