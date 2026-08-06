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
    }

    /** One payment against the sale. {@code reference} carries the PSP charge id for a CARD tender. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tender {
        private String method;       // CASH | CARD | COD | CREDIT | WALLET | BANK_TRANSFER
        private BigDecimal amount;
        private String reference;
    }
}
