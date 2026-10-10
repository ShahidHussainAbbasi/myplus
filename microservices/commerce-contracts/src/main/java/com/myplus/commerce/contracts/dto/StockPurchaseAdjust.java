package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reconcile a purchase EDIT against inventory (business-service → inventory-service). When a received purchase's
 * quantity changes, business-service sends the signed {@code delta} (newQty − oldQty) for the purchase's own
 * batch ({@code productId}+{@code batchNo}); inventory adjusts that batch AND the StockLevel by the delta so
 * batch totals, on-hand and sellable stay consistent (no StockLevel-vs-batch drift). Optional expiry/price let
 * an edit also correct those on the batch. Guarded server-side: a batch can't drop below what's already
 * reserved/sold.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StockPurchaseAdjust {
    private Long productId;
    private String batchNo;
    private Float delta;              // newQty − oldQty (may be negative)
    private LocalDate expiryDate;     // optional — update the batch's expiry too
    private BigDecimal purchasePrice; // optional — update the batch's cost too
    /** PR-3b — optional: the batch (stock_entries.id) this purchase booked in, and the price it now sells at. Applied
     *  even when the quantity did not change — an edit that only corrects the S/U rate must still re-price its batch. */
    private Long stockEntryId;
    private BigDecimal sellPrice;
    /**
     * TP-3 — what the edited bill now says about ITS batch, applied to {@link #stockEntryId} only (never to a batch found
     * by number, which another bill can share) and whatever the delta, zero included. Before this an edit that kept the
     * quantity never reached the batch: a corrected expiry, batch number or cost stayed old on it (owner.pharma@,
     * bill 3106: expiry 2027-09-09 on the bill, 2027-10-09 on its batch).
     *
     * <p>{@code paidTotal} = what the edited bill paid for its goods (rate × billed quantity). Consumption costs a unit at
     * paidTotal ÷ receivedQuantity (COGS-1), so inventory moves the batch's receivedQuantity by the same {@code delta}
     * it moves the quantity by — the two are restamped together, and the bonus (which the delta never carried) stays
     * in the divisor as received.
     */
    private String newBatchNo;
    private BigDecimal paidTotal;
}
