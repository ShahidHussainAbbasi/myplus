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
}
