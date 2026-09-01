package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One FEFO stock batch for a product (slice 54, P10): the batch/expiry a sale or dispense would draw from next,
 * with the sellable {@code available} quantity (qty − reserved). Expired batches are excluded (G1). Surfaced on the
 * dispense screen so the pharmacist sees the lot being dispensed.
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class StockBatch {
    private Long productId;
    private String batchNo;
    private LocalDate expiryDate;
    private BigDecimal available;
    private BigDecimal purchasePrice;   // the batch's last purchase price (slice M3a) — pre-fills the purchase form

    /**
     * #17 P2 — the exact amount paid for the whole batch.
     *
     * <p>Read side of the allocation: {@code purchasePrice} is per unit and therefore a rounding whenever a
     * supplier bonus made the received quantity differ from the billed one. A caller that needs the batch to
     * reconcile — COGS, a stock valuation, a gate — must use this, not quantity x unit price.
     *
     * <p>Null on batches received before the field existed, where quantity x purchasePrice IS the total.
     */
    private BigDecimal paidTotal;
}
