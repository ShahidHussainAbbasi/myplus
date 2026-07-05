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
}
