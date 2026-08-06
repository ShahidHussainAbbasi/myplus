package com.myplus.commerce.contracts.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * OMS O1 — the result half of the sale seam: what the books recorded.
 *
 * <p>{@code invoiceNo} is the whole point. The calling channel stores it on its own order so the two records
 * are permanently linked, and a reconciliation read can find any order that never produced one.
 *
 * <p>{@code grandTotal} is the SERVER's figure, returned so the channel can display and store what was actually
 * charged rather than what it computed locally. When the two disagree the server is right by definition — the
 * request has no total field at all.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SaleRecordResult {

    /** Display invoice number, e.g. INV-000123. */
    private String invoiceNo;

    /** The business-service customer_history row behind the invoice. */
    private Long customerHistoryId;

    /** RECORDED for a fresh sale, REPLAYED when the idempotency key matched an existing invoice. */
    private String status;

    /** Server-computed total for the sale. */
    private BigDecimal grandTotal;
}
