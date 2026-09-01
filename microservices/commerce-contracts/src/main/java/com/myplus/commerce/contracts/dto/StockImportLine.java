package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Opening stock for a migrated product (slice 33, U2b). business-service builds these from its local Stock
 * and POSTs them to inventory-service, which creates a StockLevel + an opening StockEntry for the productId.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class StockImportLine {
    private Long productId;
    private Float quantity;
    private String batchNo;
    private LocalDate expiryDate;
    private BigDecimal purchasePrice;
    private BigDecimal costPrice;

    /**
     * #17 P2 — what was actually PAID for this delivery line, across ALL units received.
     *
     * <p>Carried in addition to the per-unit figures because a bonus delivery breaks the assumption that
     * cost = rate x quantity: 5,000 buys 11 units under "buy 10, get 1", and any per-unit cost is then a
     * rounding of 454.545... Storing the total lets consumption allocate it exactly, so a batch expenses
     * precisely what was paid for it rather than 4,999.94 of it.
     *
     * <p>Null for every ordinary delivery, where rate x quantity IS the total and always was.
     */
    private BigDecimal paidTotal;
}
